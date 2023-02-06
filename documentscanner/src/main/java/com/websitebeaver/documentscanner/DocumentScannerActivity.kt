package com.websitebeaver.documentscanner

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import com.websitebeaver.documentscanner.constants.DefaultSetting
import com.websitebeaver.documentscanner.constants.DocumentScannerExtra
import com.websitebeaver.documentscanner.constants.ImageProvider
import com.websitebeaver.documentscanner.extensions.move
import com.websitebeaver.documentscanner.extensions.onClick
import com.websitebeaver.documentscanner.extensions.saveToFile
import com.websitebeaver.documentscanner.extensions.screenHeight
import com.websitebeaver.documentscanner.extensions.screenWidth
import com.websitebeaver.documentscanner.models.Document
import com.websitebeaver.documentscanner.models.Quad
import com.websitebeaver.documentscanner.ui.ImageCropView
import com.websitebeaver.documentscanner.utils.CameraUtil
import com.websitebeaver.documentscanner.utils.FileUtil
import com.websitebeaver.documentscanner.utils.GalleryUtil
import com.websitebeaver.documentscanner.utils.ImageUtil
import java.io.File
import org.opencv.android.OpenCVLoader
import org.opencv.core.Point

/**
 * This class contains the main document scanner code. It opens the camera, lets the user
 * take a photo of a document (homework paper, business card, etc.), detects document corners,
 * allows user to make adjustments to the detected corners, depending on options, and saves
 * the cropped document. It allows the user to do this for 1 or more documents.
 *
 * @constructor creates document scanner activity
 */
class DocumentScannerActivity : AppCompatActivity() {
    /**
     * @property maxNumDocuments maximum number of documents a user can scan at a time
     */
    private var maxNumDocuments = DefaultSetting.MAX_NUM_DOCUMENTS

    /**
     * @property letUserAdjustCrop whether or not to let user move automatically detected corners
     */
    private var letUserAdjustCrop = DefaultSetting.LET_USER_ADJUST_CROP

    /**
     * @property croppedImageQuality the 0 - 100 quality of the cropped image
     */
    private var croppedImageQuality = DefaultSetting.CROPPED_IMAGE_QUALITY

    /**
     * @property imageProvider whether to use the camera or gallery to choose documents
     */
    private var imageProvider = DefaultSetting.IMAGE_PROVIDER

    /**
     * @property cropperOffsetWhenCornersNotFound if we can't find document corners, we set
     * corners to image size with a slight margin
     */
    private val cropperOffsetWhenCornersNotFound = 100.0

    /**
     * @property isLatestPhotoCaptureSuccessful This lets us know whether or not the latest camera
     * photo attempt completed successfully. If a user captures a photo, clicks the plus button,
     * exits the camera, and presses done we don't want the final result to have 2 documents
     * instead of 1 document. A new document normally gets added each time the user clicks the
     * plus button or the done button. This variable helps us only adds a document if the camera
     * capture was successful.
     */
    private var isLatestPhotoCaptureSuccessful: Boolean = false

    /**
     * @property originalPhotoPath the photo (before crop) file path
     */
    private lateinit var originalPhotoPath: String

    /**
     * @property documents a list of documents (original photo file path and 4 corner points)
     */
    private val documents = mutableListOf<Document>()

    /**
     * @property cameraUtil gets called with photo file path once user takes photo, or
     * exits camera
     */
    private val cameraUtil = CameraUtil(
        this,
        onPhotoCaptureSuccess = {
            // user takes photo
            originalPhotoPath = it
            isLatestPhotoCaptureSuccessful = true

            // if maxNumDocuments is 3 and this is the 3rd photo, hide the new photo button since
            // we reach the allowed limit
            if (documents.size == maxNumDocuments - 1) {
                val newPhotoButton: ImageButton = findViewById(R.id.new_photo_button)
                newPhotoButton.isClickable = false
                newPhotoButton.visibility = View.INVISIBLE
            }

            // get bitmap from photo file path
            val photo: Bitmap = ImageUtil().getImageFromFilePath(originalPhotoPath)

            // get document corners by detecting them, or falling back to photo corners with
            // slight margin if we can't find the corners
            val corners = try {
                val (topLeft, topRight, bottomLeft, bottomRight) = getDocumentCorners(photo)
                Quad(topLeft, topRight, bottomRight, bottomLeft)
            } catch (exception: Exception) {
                finishIntentWithError(
                    "unable to get document corners: ${exception.message}"
                )
                return@CameraUtil
            }

            if (letUserAdjustCrop) {
                // user is allowed to move corners to make corrections
                try {
                    // set preview image height based off of photo dimensions
                    imageView.setImagePreviewBounds(photo, screenWidth, screenHeight)

                    // display original photo, so user can adjust detected corners
                    imageView.setImage(photo)

                    // display cropper, and allow user to move corners
                    imageView.setCropper(corners)
                } catch (exception: Exception) {
                    finishIntentWithError(
                        "unable get image preview ready: ${exception.message}"
                    )
                    return@CameraUtil
                }
            } else {
                // user isn't allowed to move corners, so accept automatically detected corners
                documents.add(Document(originalPhotoPath, corners))

                // create cropped document image, and return file path to complete document scan
                cropDocumentAndFinishIntent()
            }
        },
        onCancelPhoto = {
            // user exits camera
            // complete document scan if this is the first document since we can't go to crop view
            // until user takes at least 1 photo
            if (documents.isEmpty()) {
                onClickCancel()
            }
        }
    )

    /**
     * @property galleryUtil gets called with photo file path once user chooses image, or
     * exits gallery
     */
    private val galleryUtil = GalleryUtil(
        this,
        onGallerySuccess = {
            // user chooses photo
            originalPhotoPath = it
            isLatestPhotoCaptureSuccessful = true

            // if maxNumDocuments is 3 and this is the 3rd photo, hide the new photo button since
            // we reach the allowed limit
            if (documents.size == maxNumDocuments - 1) {
                val newPhotoButton: ImageButton = findViewById(R.id.new_photo_button)
                newPhotoButton.isClickable = false
                newPhotoButton.visibility = View.INVISIBLE
            }

            // get bitmap from photo file path
            val photo: Bitmap = ImageUtil().getImageFromFilePath(originalPhotoPath)

            // get document corners by detecting them, or falling back to photo corners with
            // slight margin if we can't find the corners
            val corners = try {
                val (topLeft, topRight, bottomLeft, bottomRight) = getDocumentCorners(photo)
                Quad(topLeft, topRight, bottomRight, bottomLeft)
            } catch (exception: Exception) {
                finishIntentWithError(
                    "unable to get document corners: ${exception.message}"
                )
                return@GalleryUtil
            }

            if (letUserAdjustCrop) {
                // user is allowed to move corners to make corrections
                try {
                    // set preview image height based off of photo dimensions
                    imageView.setImagePreviewBounds(photo, screenWidth, screenHeight)

                    // display original photo, so user can adjust detected corners
                    imageView.setImage(photo)

                    // display cropper, and allow user to move corners
                    imageView.setCropper(corners)
                } catch (exception: Exception) {
                    finishIntentWithError(
                        "unable get image preview ready: ${exception.message}"
                    )
                    return@GalleryUtil
                }
            } else {
                // user isn't allowed to move corners, so accept automatically detected corners
                documents.add(Document(originalPhotoPath, corners))

                // create cropped document image, and return file path to complete document scan
                cropDocumentAndFinishIntent()
            }
        },
        onCancelGallery = {
            // user exits gallery
            // complete document scan if this is the first document since we can't go to crop view
            // until user takes at least 1 photo
            if (documents.isEmpty()) {
                onClickCancel()
            }
        }
    )

    /**
     * @property imageView container with original photo and cropper
     */
    private lateinit var imageView: ImageCropView

    /**
     * called when activity is created
     *
     * @param savedInstanceState persisted data that maintains state
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            // load OpenCV
            System.loadLibrary("opencv_java4")
        } catch (exception: Exception) {
            finishIntentWithError(
                "error starting OpenCV: ${exception.message}"
            )
        }

        // Show cropper, accept crop button, add new document button, and
        // retake photo button. Since we open the camera in a few lines, the user
        // doesn't see this until they finish taking a photo
        setContentView(R.layout.activity_image_crop)
        imageView = findViewById(R.id.image_view)

        try {
            // validate maxNumDocuments option, and update default if user sets it
            var userSpecifiedMaxImages: Int? = null
            intent.extras?.get(DocumentScannerExtra.EXTRA_MAX_NUM_DOCUMENTS)?.let {
                if (it.toString().toIntOrNull() == null) {
                    throw Exception(
                        "${DocumentScannerExtra.EXTRA_MAX_NUM_DOCUMENTS} must be a positive number"
                    )
                }
                userSpecifiedMaxImages = it as Int
                maxNumDocuments = userSpecifiedMaxImages as Int
            }

            // validate letUserAdjustCrop option, and update default if user sets it
            intent.extras?.get(DocumentScannerExtra.EXTRA_LET_USER_ADJUST_CROP)?.let {
                if (!arrayOf("true", "false").contains(it.toString())) {
                    throw Exception(
                        "${DocumentScannerExtra.EXTRA_LET_USER_ADJUST_CROP} must true or false"
                    )
                }
                letUserAdjustCrop = it as Boolean
            }

            // if we don't want user to move corners, we can let the user only take 1 photo
            if (!letUserAdjustCrop) {
                maxNumDocuments = 1

                if (userSpecifiedMaxImages != null && userSpecifiedMaxImages != 1) {
                    throw Exception(
                        "${DocumentScannerExtra.EXTRA_MAX_NUM_DOCUMENTS} must be 1 when " +
                                "${DocumentScannerExtra.EXTRA_LET_USER_ADJUST_CROP} is false"
                    )
                }
            }

            // validate croppedImageQuality option, and update value if user sets it
            intent.extras?.get(DocumentScannerExtra.EXTRA_CROPPED_IMAGE_QUALITY)?.let {
                if (it !is Int || it < 0 || it > 100) {
                    throw Exception(
                        "${DocumentScannerExtra.EXTRA_CROPPED_IMAGE_QUALITY} must be a number " +
                                "between 0 and 100"
                    )
                }
                croppedImageQuality = it
            }

            // validate imageProvider option, and update default if user sets it
            intent.extras?.get(DocumentScannerExtra.EXTRA_IMAGE_PROVIDER)?.let {
                if (!arrayOf(ImageProvider.CAMERA, ImageProvider.GALLERY).contains(it.toString())) {
                    throw Exception("${DocumentScannerExtra.EXTRA_LET_USER_ADJUST_CROP} must be either camera or gallery")
                }
                imageProvider = it as String
            }
        } catch (exception: Exception) {
            finishIntentWithError(
                "invalid extra: ${exception.message}"
            )
            return
        }

        // set click event handlers for new document button, accept and crop document button,
        // and retake document photo button
        val newPhotoButton: ImageButton = findViewById(R.id.new_photo_button)
        val completeDocumentScanButton: ImageButton = findViewById(
            R.id.complete_document_scan_button
        )
        val retakePhotoButton: ImageButton = findViewById(R.id.retake_photo_button)

        newPhotoButton.onClick { onClickNew() }
        completeDocumentScanButton.onClick { onClickDone() }
        retakePhotoButton.onClick { onClickRetake() }

        // open camera or gallery, so user can snap or choose document photo
        if (imageProvider == ImageProvider.CAMERA) {
            try {
                openCamera()
            } catch (exception: Exception) {
                finishIntentWithError("error opening camera: ${exception.message}")
            }
        } else if (imageProvider == ImageProvider.GALLERY) {
            try {
                openGallery()
            } catch (exception: Exception) {
                finishIntentWithError("error opening gallery: ${exception.message}")
            }
        }
    }

    /**
     * called when we return to activity (user switches to another app and then returns
     * for example)
     */
    override fun onResume() {
        super.onResume()
    }

    /**
     * Pass in a photo of a document, and get back 4 corner points (top left, top right, bottom
     * right, bottom left). This tries to detect document corners, but falls back to photo corners
     * with slight margin in case we can't detect document corners.
     *
     * @param photo the original photo with a rectangular document
     * @return a List of 4 OpenCV points (document corners)
     */
    private fun getDocumentCorners(photo: Bitmap): List<Point> {
        val cornerPoints: List<Point>? = DocumentDetector().findDocumentCorners(photo)

        // if cornerPoints is null then default the corners to the photo bounds with a margin
        return cornerPoints ?: listOf(
            Point(0.0, 0.0).move(
                cropperOffsetWhenCornersNotFound,
                cropperOffsetWhenCornersNotFound
            ),
            Point(photo.width.toDouble(), 0.0).move(
                -cropperOffsetWhenCornersNotFound,
                cropperOffsetWhenCornersNotFound
            ),
            Point(0.0, photo.height.toDouble()).move(
                cropperOffsetWhenCornersNotFound,
                -cropperOffsetWhenCornersNotFound
            ),
            Point(photo.width.toDouble(), photo.height.toDouble()).move(
                -cropperOffsetWhenCornersNotFound,
                -cropperOffsetWhenCornersNotFound
            )
        )
    }

    /**
     * Set isLatestPhotoCaptureSuccessful to false, and open the camera. If the user captures
     * a photo successfully isLatestPhotoCaptureSuccessful gets set to true.
     */
    private fun openCamera() {
        isLatestPhotoCaptureSuccessful = false
        cameraUtil.openCamera(documents.size)
    }

    /**
     * Set isLatestPhotoCaptureSuccessful to false, and open the gallery. If the user chooses
     * a photo successfully isLatestPhotoCaptureSuccessful gets set to true.
     */
    private fun openGallery() {
        isLatestPhotoCaptureSuccessful = false
        galleryUtil.openGallery(documents.size)
    }

    /**
     * Once user accepts by pressing check button, or by pressing add new document button, add
     * original photo path and 4 document corners to documents list. If user isn't allowed to
     * adjust corners, call this automatically.
     */
    private fun addSelectedCornersAndOriginalPhotoPathToDocuments() {
        if (isLatestPhotoCaptureSuccessful) {
            documents.add(Document(originalPhotoPath, imageView.corners))
        }
    }

    /**
     * This gets called when a user presses the new document button. Store current photo path
     * with document corners. Then open the camera, so user can take a photo of the next
     * page or document
     */
    private fun onClickNew() {
        addSelectedCornersAndOriginalPhotoPathToDocuments()
        if (imageProvider == ImageProvider.CAMERA) {
            openCamera()
        } else if (imageProvider == ImageProvider.GALLERY) {
            openGallery()
        }
    }

    /**
     * This gets called when a user presses the done button. Store current photo path with
     * document corners. Then crop document using corners, and return cropped image paths
     */
    private fun onClickDone() {
        addSelectedCornersAndOriginalPhotoPathToDocuments()
        cropDocumentAndFinishIntent()
    }

    /**
     * This gets called when a user presses the retake photo button. The user presses this in
     * case the original document photo isn't good, and they need to take it again.
     */
    private fun onClickRetake() {
        if (imageProvider == ImageProvider.CAMERA) {
            openCamera()
        } else if (imageProvider == ImageProvider.GALLERY) {
            openGallery()
        }
    }

    /**
     * This gets called when a user doesn't want to complete the document scan after starting.
     * For example a user can quit out of the camera before snapping a photo of the document.
     */
    private fun onClickCancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    /**
     * This crops original document photo, saves cropped document photo, deletes original
     * document photo, and returns cropped document photo file path. It repeats that for
     * all document photos.
     */
    private fun cropDocumentAndFinishIntent() {
        val croppedImageResults = arrayListOf<String>()
        for ((pageNumber, document) in documents.withIndex()) {
            // crop document photo by using corners
            val croppedImage: Bitmap = try {
                ImageUtil().crop(
                    document.originalPhotoFilePath,
                    document.corners
                )
            } catch (exception: Exception) {
                finishIntentWithError("unable to crop image: ${exception.message}")
                return
            }

            // delete original document photo
            File(document.originalPhotoFilePath).delete()

            // save cropped document photo
            try {
                val croppedImageFile = FileUtil().createImageFile(this, pageNumber)
                croppedImage.saveToFile(croppedImageFile, croppedImageQuality)
                croppedImageResults.add(Uri.fromFile(croppedImageFile).toString())
            } catch (exception: Exception) {
                finishIntentWithError(
                    "unable to save cropped image: ${exception.message}"
                )
            }
        }

        // return array of cropped document photo file paths
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra("croppedImageResults", croppedImageResults)
        )
        finish()
    }

    /**
     * This ends the document scanner activity, and returns an error message that can be
     * used to debug error
     *
     * @param errorMessage an error message
     */
    private fun finishIntentWithError(errorMessage: String) {
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra("error", errorMessage)
        )
        finish()
    }
}