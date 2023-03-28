package com.otaliastudios.transcoder.demo

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.common.TrackStatus
import com.otaliastudios.transcoder.common.TrackType
import com.otaliastudios.transcoder.demo.databinding.ActivityTranscoderBinding
import com.otaliastudios.transcoder.internal.utils.Logger
import com.otaliastudios.transcoder.resize.AspectRatioResizer
import com.otaliastudios.transcoder.resize.FractionResizer
import com.otaliastudios.transcoder.resize.PassThroughResizer
import com.otaliastudios.transcoder.source.DataSource
import com.otaliastudios.transcoder.source.TrimDataSource
import com.otaliastudios.transcoder.source.UriDataSource
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import com.otaliastudios.transcoder.strategy.RemoveTrackStrategy
import com.otaliastudios.transcoder.strategy.TrackStrategy
import com.otaliastudios.transcoder.validator.DefaultValidator
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Future
import kotlin.math.roundToInt

class TranscoderActivity : AppCompatActivity(), TranscoderListener {

    private lateinit var binding: ActivityTranscoderBinding

    private var mIsTranscoding = false
    private var mIsAudioOnly = false
    private var mTranscodeFuture: Future<Void>? = null
    private var mAudioReplacementUri: Uri? = null
    private var mTranscodeOutputFile: File? = null
    private var mTranscodeStartTime: Long = 0
    private var mTranscodeVideoStrategy: TrackStrategy? = null
    private var mTranscodeAudioStrategy: TrackStrategy? = null
    private var mTrimStartUs: Long = 0
    private var mTrimEndUs: Long = 0

    private val mRadioGroupListener =
        RadioGroup.OnCheckedChangeListener { _: RadioGroup?, _: Int -> syncParameters() }

    private val mTextListener: TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable) {
            syncParameters()
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTranscoderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setIsTranscoding(false)
        binding.progress.max = PROGRESS_BAR_MAX

        syncParameters()

        configListeners()
    }

    private fun configListeners() {
        binding.button.setOnClickListener {
            if (!mIsTranscoding) {
                val intent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                startActivityForResult(intent, REQUEST_CODE_PICK)
            } else {
                mTranscodeFuture!!.cancel(true)
            }
        }
        binding.channels.setOnCheckedChangeListener(mRadioGroupListener)
        binding.frames.setOnCheckedChangeListener(mRadioGroupListener)
        binding.resolution.setOnCheckedChangeListener(mRadioGroupListener)
        binding.aspect.setOnCheckedChangeListener(mRadioGroupListener)
        binding.sampleRate.setOnCheckedChangeListener(mRadioGroupListener)
        binding.trimStart.addTextChangedListener(mTextListener)
        binding.trimEnd.addTextChangedListener(mTextListener)
        binding.replace.setOnCheckedChangeListener { group: RadioGroup?, checkedId: Int ->
            mAudioReplacementUri = null
            binding.replaceInfo.text = "No replacement selected."
            if (checkedId == R.id.replace_yes) {
                if (!mIsTranscoding) {
                    startActivityForResult(
                        Intent(Intent.ACTION_GET_CONTENT)
                            .setType("audio/*"), REQUEST_CODE_PICK_AUDIO
                    )
                }
            }
            mRadioGroupListener.onCheckedChanged(group, checkedId)
        }
    }

    private fun syncParameters() {
        val channels: Int = when (binding.frames.checkedRadioButtonId) {
            R.id.channels_mono -> 1
            R.id.channels_stereo -> 2
            else -> DefaultAudioStrategy.CHANNELS_AS_INPUT
        }
        val sampleRate: Int = when (binding.frames.checkedRadioButtonId) {
            R.id.sampleRate_32 -> 32000
            R.id.sampleRate_48 -> 48000
            else -> DefaultAudioStrategy.SAMPLE_RATE_AS_INPUT
        }
        val removeAudio: Boolean = when (binding.replace.checkedRadioButtonId) {
            R.id.replace_remove -> true
            R.id.replace_yes -> false
            else -> false
        }
        mTranscodeAudioStrategy = if (removeAudio) {
            RemoveTrackStrategy()
        } else {
            DefaultAudioStrategy.builder()
                .channels(channels)
                .sampleRate(sampleRate)
                .build()
        }
        val frames: Int = when (binding.frames.checkedRadioButtonId) {
            R.id.frames_24 -> 24
            R.id.frames_30 -> 30
            R.id.frames_60 -> 60
            else -> DefaultVideoStrategy.DEFAULT_FRAME_RATE
        }
        val fraction: Float = when (binding.resolution.checkedRadioButtonId) {
            R.id.resolution_half -> 0.5f
            R.id.resolution_third -> 1f / 3f
            else -> 1f
        }
        val aspectRatio: Float = when (binding.aspect.checkedRadioButtonId) {
            R.id.aspect_169 -> 16f / 9f
            R.id.aspect_43 -> 4f / 3f
            R.id.aspect_square -> 1f
            else -> 0f
        }
        mTranscodeVideoStrategy = DefaultVideoStrategy.Builder()
            .addResizer(if (aspectRatio > 0) AspectRatioResizer(aspectRatio) else PassThroughResizer())
            .addResizer(FractionResizer(fraction))
            .frameRate(frames)
            .bitRate(1) // .keyFrameInterval(4F)
            .build()
        try {
            mTrimStartUs = java.lang.Long.valueOf(binding.trimStart.text.toString()) * 1000000
        } catch (e: NumberFormatException) {
            mTrimStartUs = 0
            LOG.w("Failed to read trimStart value.", e)
        }
        try {
            mTrimEndUs = java.lang.Long.valueOf(binding.trimEnd.text.toString()) * 1000000
        } catch (e: NumberFormatException) {
            mTrimEndUs = 0
            LOG.w("Failed to read trimEnd value.", e)
        }
        if (mTrimStartUs < 0) mTrimStartUs = 0
        if (mTrimEndUs < 0) mTrimEndUs = 0
    }

    private fun setIsTranscoding(isTranscoding: Boolean) {
        mIsTranscoding = isTranscoding
        binding.trimStart.setText(if (mIsTranscoding) "Cancel Transcoding" else "Select Video & Transcode")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK && resultCode == RESULT_OK && data != null) {
            if (data.clipData != null) {
                val clipData = data.clipData
                val uris: MutableList<Uri> = ArrayList()
                for (i in 0 until clipData!!.itemCount) {
                    uris.add(clipData.getItemAt(i).uri)
                }
                transcode(*uris.toTypedArray())
            } else if (data.data != null) {
                transcode(data.data!!)
            }
        }
        if (requestCode == REQUEST_CODE_PICK_AUDIO && resultCode == RESULT_OK && data != null && data.data != null) {
            mAudioReplacementUri = data.data
            binding.replaceInfo.text = mAudioReplacementUri.toString()
        }
    }

    private fun createVideoFile(): File? {
        val timeStamp = SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale("pt", "BR")).format(Date())
        val videoFileName = "VIDEO_" + timeStamp + "_"
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        var video: File? = null
        try {
            video = File.createTempFile(videoFileName, ".mp4", storageDir)
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return video
    }

    private fun transcode(vararg uris: Uri) {
        mTranscodeOutputFile = createVideoFile()
        val rotation: Int = when (binding.rotation.checkedRadioButtonId) {
            R.id.rotation_90 -> 90
            R.id.rotation_180 -> 180
            R.id.rotation_270 -> 270
            else -> 0
        }
        val speed: Float = when (binding.speed.checkedRadioButtonId) {
            R.id.speed_05x -> 0.5f
            R.id.speed_2x -> 2f
            else -> 1f
        }

        // Launch the transcoding operation.
        mTranscodeStartTime = SystemClock.uptimeMillis()
        setIsTranscoding(true)
        LOG.e("Building transcoding options...")
        val builder = Transcoder.into(mTranscodeOutputFile!!.absolutePath)
        val sources = mutableListOf<DataSource>()
        for (uri in uris) {
            sources.add(UriDataSource(this, uri))
        }
        sources[0] = TrimDataSource(sources[0], mTrimStartUs, mTrimEndUs)
        if (mAudioReplacementUri == null) {
            for (source in sources) {
                builder.addDataSource(source)
            }
        } else {
            for (source in sources) {
                builder.addDataSource(TrackType.VIDEO, source)
            }
            builder.addDataSource(TrackType.AUDIO, this, mAudioReplacementUri!!)
        }
        LOG.e("Starting transcoding!")
        mTranscodeFuture = builder.setListener(this)
            .setAudioTrackStrategy(mTranscodeAudioStrategy)
            .setVideoTrackStrategy(mTranscodeVideoStrategy)
            .setVideoRotation(rotation)
            .setValidator(object : DefaultValidator() {
                override fun validate(videoStatus: TrackStatus, audioStatus: TrackStatus): Boolean {
                    mIsAudioOnly = !videoStatus.isTranscoding
                    return super.validate(videoStatus, audioStatus)
                }
            })
            .setSpeed(speed)
            .transcode()
    }

    override fun onTranscodeProgress(progress: Double) {
        if (progress < 0) {
            binding.progress.isIndeterminate = true
        } else {
            binding.progress.isIndeterminate = false
            binding.progress.progress = (progress * PROGRESS_BAR_MAX).roundToInt()
        }
    }

    override fun onTranscodeCompleted(successCode: Int) {
        if (successCode == Transcoder.SUCCESS_TRANSCODED) {
            LOG.w("Transcoding took " + (SystemClock.uptimeMillis() - mTranscodeStartTime) + "ms")
            onTranscodeFinished(true, "Transcoded file placed on $mTranscodeOutputFile")
            val file = mTranscodeOutputFile
            val fileSizeInBytes = file!!.length()
            val fileSizeInMB = fileSizeInBytes.toDouble() / (1024 * 1024)
            Log.i("INFOTESTE", "onTranscodeCompleted: $fileSizeInMB")
            val type = if (mIsAudioOnly) "audio/mp4" else "video/mp4"
            val uri = FileProvider.getUriForFile(
                this@TranscoderActivity,
                FILE_PROVIDER_AUTHORITY, file
            )
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, type)
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } else if (successCode == Transcoder.SUCCESS_NOT_NEEDED) {
            LOG.i("Transcoding was not needed.")
            onTranscodeFinished(true, "Transcoding not needed, source file untouched.")
        }
    }

    override fun onTranscodeCanceled() {
        onTranscodeFinished(false, "Transcoder canceled.")
    }

    override fun onTranscodeFailed(exception: Throwable) {
        onTranscodeFinished(false, "Transcoder error occurred. " + exception.message)
    }

    private fun onTranscodeFinished(isSuccess: Boolean, toastMessage: String) {
        binding.progress.isIndeterminate = false
        binding.progress.progress = if (isSuccess) PROGRESS_BAR_MAX else 0
        setIsTranscoding(false)
        Toast.makeText(this@TranscoderActivity, toastMessage, Toast.LENGTH_LONG).show()
    }

    companion object {
        private val LOG = Logger("TranscoderActivity")
        private const val FILE_PROVIDER_AUTHORITY = "com.otaliastudios.transcoder.demo"
        private const val REQUEST_CODE_PICK = 1
        private const val REQUEST_CODE_PICK_AUDIO = 5
        private const val PROGRESS_BAR_MAX = 1000
    }
}