package com.classschedule.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream

/**
 * 背景图文件操作的**端口接口**（v0.12.0）。
 *
 * 抽接口的动机与 [CourseStore] 一样：ViewModel 依赖它就能在纯 JVM 单测里
 * 替换成内存假实现，不必准备真实 `Context` 与 `filesDir`。
 * 生产环境唯一实现是 [BackgroundImageStore]。
 */
interface BackgroundImagePort {
    /**
     * 导入选中的图片；[previousFileName] 是要被替换掉的旧图（可空）。
     *
     * [uri] 声明为可空是为了让纯 JVM 单测能驱动 ViewModel 的编排逻辑：
     * android.jar 是桩实现，测试里造不出真实 [Uri]。真实实现遇到 null
     * 应当直接返回失败而不是崩。
     */
    suspend fun import(uri: Uri?, previousFileName: String?): BackgroundImageStore.ImportResult

    /** 读回背景位图；文件不存在或读失败返回 null */
    suspend fun load(fileName: String?): ImageBitmap?

    /** 删除背景图文件 */
    suspend fun delete(fileName: String?)
}

/**
 * 自定义背景图的落盘与解码（v0.12.0）。
 *
 * ## 为什么必须把 SAF 的 Uri 拷进私有目录
 *
 * `ACTION_OPEN_DOCUMENT` 返回的 Uri **不保证长期有效**：它由系统文件选择器授予，
 * 重启后可能失效（取决于用户是否保留了授权）。而外观配置要存在数据库里长期使用，
 * 不能存 Uri。因此流程是：SAF 选图 → 立刻解码并**重编码**成 JPEG 存进
 * `filesDir/appearance/` → 数据库只存文件名。
 *
 * 顺带的好处：重编码时按屏幕尺寸降采样，原图 4000×3000 的 8 MB 大图
 * 会被压成 1080 宽的约 200 KB，既省空间也避免每次开界面都解码大图吃内存。
 *
 * ## 权限
 *
 * 全程不申请任何存储权限：读用 SAF 授予的一次性 Uri 读流，
 * 写用 `context.filesDir`（应用私有，无需权限）。
 *
 * ## 为什么用重编码而不是直接拷贝原文件
 *
 * 直接拷贝能保留原始画质，但有两个问题：一是文件可能高达十几 MB，
 * 二是解码时要处理 EXIF 旋转与超大图 OOM。重编码时统一按显示尺寸降采样，
 * 把这些都在导入这一步一次性解决掉。
 */
object BackgroundImageStore {

    /** 私有目录名（相对 `filesDir`） */
    const val DIR_NAME = "appearance"

    /**
     * 目标最长边（px）。
     *
     * 1080 是按"绝大多数手机竖屏宽度 × 3 倍密度"定的：再小在 2K 屏上会糊，
     * 再大对一张课表背景没有额外收益（背景图本来就是被半透明蒙版压着的）。
     */
    const val TARGET_LONG_EDGE = 1080

    /** JPEG 重编码质量：85 是"看不出压缩痕迹"与"文件别太大"的平衡点 */
    private const val JPEG_QUALITY = 85

    /** 导入结果 */
    sealed interface ImportResult {
        /** 成功，[fileName] 是存进 [DIR_NAME] 的文件名（不含目录） */
        data class Success(val fileName: String) : ImportResult

        /** 失败，[reason] 直接展示给用户看 */
        data class Failure(val reason: String) : ImportResult
    }

    /** 背景图私有目录，不存在则创建 */
    fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /**
     * 从 SAF 选中的 Uri 导入背景图。
     *
     * 全程应在 IO 线程调用。旧背景图在**新图成功落盘之后**才删除——
     * 先删后拷一旦失败，用户就两头空（配置里还有路径，文件却没了）。
     */

    /** 绑定到 Context 的实现，供 ViewModel 通过 [BackgroundImagePort] 使用 */
    fun of(context: Context): BackgroundImagePort = Bound(context.applicationContext)

    /** 绑定了 applicationContext 的实现（object 本身无状态，操作都经由它） */
    private class Bound(private val appContext: Context) : BackgroundImagePort {
        override suspend fun import(uri: Uri?, previousFileName: String?): ImportResult =
            importInternal(appContext, uri, previousFileName)

        override suspend fun load(fileName: String?): ImageBitmap? =
            loadInternal(appContext, fileName)

        override suspend fun delete(fileName: String?) = deleteInternal(appContext, fileName)
    }

    private fun importInternal(context: Context, uri: Uri?, previousFileName: String?): ImportResult {
        // 可空是给单测留的口子（见 BackgroundImagePort 的说明）；真实现必须挡住
        if (uri == null) return ImportResult.Failure("没有选中图片")
        val source = decodeSampled(context, uri)
            ?: return ImportResult.Failure("无法读取这张图片，换一张试试")

        val fileName = "bg_${System.currentTimeMillis()}.jpg"
        val target = File(dir(context), fileName)
        val saved = try {
            FileOutputStream(target).use { out ->
                source.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            source.recycle()
        }

        if (!saved) {
            target.delete() // 别留下半个文件
            return ImportResult.Failure("保存图片失败，请重试")
        }

        // 新图确认落盘后才清旧的
        previousFileName?.let { if (it != fileName) deleteInternal(context, it) }
        return ImportResult.Success(fileName)
    }

    /**
     * 解码到目标尺寸以内的 Bitmap。
     *
     * 两趟解码：先只读尺寸（`inJustDecodeBounds`）算出 `inSampleSize`，
     * 再真正解码。这是 Android 处理大图的标准做法，避免原图 20 MB 直接进内存。
     */
    private fun decodeSampled(context: Context, uri: Uri): Bitmap? = try {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, TARGET_LONG_EDGE)
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        // 解大图仍可能 OOM（inSampleSize 只是抽样，不是缩放）
        null
    }

    /**
     * 降采样倍数（2 的幂）。
     *
     * 抽成纯函数是为了能单测——这个数字算错的后果是"图被放大后糊掉"
     * 或"降采样过头导致背景细节丢失"，真机上看一眼很难归因。
     *
     * 算法：让采样后的最长边**不小于**目标长边（而不是小于），
     * 这样结果只会略微过采样，不会欠采样导致放大发虚。
     */
    fun computeSampleSize(srcWidth: Int, srcHeight: Int, targetLongEdge: Int): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || targetLongEdge <= 0) return 1
        val longEdge = maxOf(srcWidth, srcHeight)
        var sample = 1
        while (longEdge / (sample * 2) >= targetLongEdge) {
            sample *= 2
        }
        return sample
    }

    /**
     * 读回背景图为 [ImageBitmap]，供 Compose 绘制。
     *
     * 文件不存在（用户清过数据、或配置来自旧版本）返回 null，
     * 调用方据此回落到纯色背景，而不是崩掉。
     */
    private fun loadInternal(context: Context, fileName: String?): ImageBitmap? {
        if (fileName.isNullOrBlank()) return null
        val file = File(dir(context), fileName)
        if (!file.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, TARGET_LONG_EDGE)
            }
            BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /**
     * 删除背景图文件。
     *
     * 只删 `filesDir/appearance/` 下、且**文件名本身通过安全校验**的文件：
     * 不做目录穿越防护的话，一个 `../../databases/xxx` 的配置值就能删掉数据库文件。
     */
private fun deleteInternal(context: Context, fileName: String?) {
        if (fileName.isNullOrBlank()) return
        if (!AppearanceCodec.isSafePath(fileName)) return
        runCatching { File(dir(context), fileName).delete() }
    }
}