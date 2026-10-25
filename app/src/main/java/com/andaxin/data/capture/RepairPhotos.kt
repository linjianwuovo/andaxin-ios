package com.andaxin.data.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sqrt

/*
 * 报修那张单要传的图，本机这一半：读出来 → 按 EXIF 转正 → 缩到不超 400 万像素 → 转 base64。
 *
 * 【为什么是 base64 而不是 multipart】见 `docs/接入清单.md` 一点三之二十八。
 * 官方 H5 那段 `photograph` 组件里的 `postImg` 发的是 `{fileImg: <dataURL>}`，
 * 走的是报修这一族同一个 `request.post`，根本不是文件上传口。
 * 那几道判据也照读来的数写死，不自己另拍一套：
 * - 原图 > 5MB 客户端就弹"图片大小必须小于5MB"，不收；
 * - 宽×高 > 4e6（400 万像素）才缩边；
 * - 条数 > 10 弹"最多只能上传10张图片"；
 * - 转正那一步它用 `rotateImg` 读 EXIF `Orientation`，我们这边同一件事。
 *
 * 【这一发我们一次没打过】上传那一口学校收不收我们的 base64、会不会再拿 `_userType`/`loginType`
 * 卡一道，都得他真按一次才知道。所以这一屏所有失败都报原话或报"第几张卡住"，不静默少传。
 */
object RepairPhotos {

    /** 官方那道条数闸 —— 数定在共享层（提交前那边也按它拦），这儿只是引一下。 */
    const val MAX_COUNT = REPAIR_MAX_PHOTOS

    /** 官方那道单张大小闸：5MB，按 1024 算。 */
    const val MAX_ORIGINAL_BYTES = 5_242_880L

    private const val MAX_PIXELS = 4_000_000L
    private const val QUALITY = 85

    /**
     * 一张本机的图 → `data:image/jpeg;base64,…`，直接进 `fileImg` 那个字段。
     *
     * 失败原因写成一句人话，并且**带着是第几张的位置信息在界面上报**（调用点那边拼）。
     * 转成 JPEG 是跟着官方走的：它 canvas 压完也是 `toDataURL("image/jpeg")`。
     * 整段走 IO 线程：一张一千二百万像素的原图 decode 出来是几百兆的事，别在主线程上干。
     */
    suspend fun encode(context: Context, uri: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            querySize(context, uri)?.let {
                if (it > MAX_ORIGINAL_BYTES) error("${mb(it)} 的原图超过 5MB（学校那一版也是这个闸）")
            }
            val bytes = context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
                ?: error("这张图本机读不出来（文件被删了或没给权限）")
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampleOptions(bytes))
                ?: error("这张不是本机认得的图片格式")
            val turned = turn(raw, orientationOf(bytes))
            val scaled = shrink(turned)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            if (scaled !== raw) scaled.recycle()
            if (turned !== raw) turned.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }

    /**
     * 只给界面上那一格缩略图用：按 2 的幂降到够画就行，不压质量。
     *
     * 但 EXIF 转正这一步不能省 —— 上传那一发会转正（`encode` 里走 `turn`），
     * 预览不转的话，拍完竖着拍的在格子里就是横的，人以为传错了。
     */
    fun thumb(context: Context, uri: String, edgePx: Int): Bitmap? = runCatching {
        val bytes = context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
            ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edgePx) sample *= 2
        val small = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        turn(small, orientationOf(bytes))
    }.getOrNull()

    /** 拿不到 SIZE 列（有的提供方没有）就当没这条闸，交给后面 decode 那一步。 */
    private fun querySize(context: Context, uri: String): Long? = runCatching {
        context.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    }.getOrNull()

    /**
     * 先按 2 的幂降采样，别让一张一亿像素的原图整个进内存。
     *
     * 判据是 `原像素 / n² > 上限就把 n 翻倍` —— 解码器按 inSampleSize=n 出来的就是 `P/n²` 个像素。
     * 写成除以 `(2n)²` 会提前停下：一张 4000×3000（12M）的会拿 n=1 整个解出来，
     * ARGB_8888 就是 36MB，十张连着传能把这一屏的进程顶到被系统回收。
     */
    private fun sampleOptions(bytes: ByteArray): BitmapFactory.Options {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val px = bounds.outWidth.toLong() * bounds.outHeight
        var sample = 1
        while (px / (sample.toLong() * sample) > MAX_PIXELS) sample *= 2
        return BitmapFactory.Options().apply { inSampleSize = sample }
    }

    private fun orientationOf(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** EXIF 里那几个方位码 → 一次矩阵变换。`ORIENTATION_NORMAL`/没标记的原样返回。 */
    private fun turn(bmp: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun shrink(bmp: Bitmap): Bitmap {
        val px = bmp.width.toLong() * bmp.height
        if (px <= MAX_PIXELS) return bmp
        val k = sqrt(MAX_PIXELS.toDouble() / px).toFloat()
        return Bitmap.createScaledBitmap(
            bmp,
            max(1, (bmp.width * k).toInt()),
            max(1, (bmp.height * k).toInt()),
            true,
        )
    }

    private fun mb(bytes: Long) = "%.1fMB".format(bytes / 1024.0 / 1024.0)
}
