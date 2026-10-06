package com.mediaviewer.ui.compat

import com.mediaviewer.platform.IosPaths
import com.mediaviewer.platform.IosUri
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.platform.randomUuidString
import com.mediaviewer.platform.toByteArray
import com.mediaviewer.platform.writeLocalFile
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerMediaURL
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerQualityTypeHigh
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.darwin.NSObject

/**
 * The camera on iOS: Apple's own camera page (the one the Camera app's
 * controls come from — photo / video switch, flip, flash, zoom), opened
 * over Stellar. Whatever is taken comes back as a file in Stellar's cache.
 *
 * Needs NSCameraUsageDescription and NSMicrophoneUsageDescription in
 * Info.plist (iOS closes an app that opens the camera without them).
 */
@OptIn(ExperimentalForeignApi::class)
object IosCamera {
    // UIKit only keeps a weak reference to the delegate: held here.
    private var delegate: NSObject? = null

    fun available(): Boolean = runCatching {
        UIImagePickerController.isSourceTypeAvailable(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera)
    }.getOrDefault(false)

    /**
     * Opens the camera. [onResult] gets the photo or the video taken (one
     * of the two), or two nulls when it was closed without taking
     * anything. Returns false when this device has no camera to open.
     */
    fun open(onResult: (image: PlatformUri?, video: PlatformUri?) -> Unit): Boolean {
        if (!available()) return false
        var top = IosScreen.keyWindow()?.rootViewController ?: return false
        while (true) top = top.presentedViewController ?: break

        val picker = UIImagePickerController()
        picker.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
        // Photos and videos, like Android's camera page.
        picker.mediaTypes = listOf("public.image", "public.movie")
        picker.videoQuality = UIImagePickerControllerQualityTypeHigh
        picker.modalPresentationStyle = UIModalPresentationFullScreen
        val handler = object : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
            override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
                delegate = null
                val movie = didFinishPickingMediaWithInfo[UIImagePickerControllerMediaURL] as? NSURL
                val photo = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
                // (Copied out before the camera closes: its own file is
                // temporary.)
                val video = movie?.let { keepMovie(it) }
                val image = if (video == null) photo?.let { keepPhoto(it) } else null
                picker.dismissViewControllerAnimated(true) { onResult(image, video) }
            }

            override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
                delegate = null
                picker.dismissViewControllerAnimated(true) { onResult(null, null) }
            }
        }
        delegate = handler
        picker.delegate = handler
        top.presentViewController(picker, animated = true, completion = null)
        return true
    }

    private fun captureDir(): String {
        val dir = IosPaths.cacheDir() + "/captures"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return dir
    }

    private fun keepMovie(source: NSURL): PlatformUri? {
        val ext = (source.pathExtension ?: "").ifBlank { "mov" }
        val dest = captureDir() + "/video_" + randomUuidString() + "." + ext
        val ok = NSFileManager.defaultManager.copyItemAtURL(source, toURL = NSURL.fileURLWithPath(dest), error = null)
        return if (ok) IosUri("file://$dest") else null
    }

    private fun keepPhoto(image: UIImage): PlatformUri? {
        val bytes = UIImageJPEGRepresentation(image, 0.92)?.toByteArray() ?: return null
        val dest = captureDir() + "/photo_" + randomUuidString() + ".jpg"
        return if (writeLocalFile(dest, bytes)) IosUri("file://$dest") else null
    }
}
