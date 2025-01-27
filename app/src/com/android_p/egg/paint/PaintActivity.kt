/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.errortek.paint

import android.app.Activity
import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Magnifier
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.errortek.paint.color.ColorAdapter
import com.errortek.paint.color.GridSpacingItemDecoration
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Objects
import kotlin.math.pow

class PaintActivity : Activity() {
    private var painting: Painting? = null
    private var toolbar: CutoutAvoidingToolbar? = null
    private var brushes: LinearLayout? = null
    private var colors: LinearLayout? = null
    private var magnifier: Magnifier? = null
    private var sampling = false
    private lateinit var colorPickerDialog: AlertDialog

    private val buttonHandler = View.OnClickListener { view ->
        val id = view.id
        if (id == R.id.btnBrush) {
            view.isSelected = true
            hideToolbar(colors)
            toggleToolbar(brushes)
        } else if (id == R.id.btnColor) {
            view.isSelected = true
            hideToolbar(brushes)
            toggleToolbar(colors)
        } else if (id == R.id.btnClear) {
            painting!!.clear()
        } else if (id == R.id.btnSample) {
            sampling = true
            view.isSelected = true
        } else if (id == R.id.btnZen) {
            painting!!.zenMode = !painting!!.zenMode
            view.animate()
                .setStartDelay(200)
                .setInterpolator(OvershootInterpolator())
                .rotation(if (painting!!.zenMode) 0f else 90f)
        }
    }

    private fun showToolbar(bar: View?) {
        if (bar!!.visibility != View.GONE) return
        bar.visibility = View.VISIBLE
        bar.translationY = (toolbar!!.height / 2).toFloat()
        bar.animate()
            .translationY(toolbar!!.height.toFloat())
            .alpha(1f)
            .setDuration(220)
            .start()
    }

    private fun hideToolbar(bar: View?) {
        if (bar!!.visibility != View.VISIBLE) return
        bar.animate()
            .translationY((toolbar!!.height / 2).toFloat())
            .alpha(0f)
            .setDuration(150)
            .withEndAction { bar.visibility = View.GONE }
            .start()
    }

    private fun toggleToolbar(bar: View?) {
        if (bar!!.visibility == View.VISIBLE) {
            hideToolbar(bar)
        } else {
            showToolbar(bar)
        }
    }

    private var widthButtonDrawable: BrushPropertyDrawable? = null
    private var colorButtonDrawable: BrushPropertyDrawable? = null
    private var maxBrushWidth = 0f
    private var minBrushWidth = 0f
    private var nightMode = Configuration.UI_MODE_NIGHT_UNDEFINED

    fun setupViews(oldPainting: Painting?) {
        setContentView(R.layout.p_activity_paint)

        painting = oldPainting ?: Painting(this)
        (findViewById<View>(R.id.contentView) as FrameLayout).addView(
            painting,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        painting!!.paperColor = ContextCompat.getColor(this, R.color.p_paper_color)
        painting!!.setPaintColor(ContextCompat.getColor(this, R.color.p_paint_color))

        toolbar = findViewById(R.id.toolbar)
        brushes = findViewById(R.id.brushes)
        colors = findViewById(R.id.colors)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            magnifier = Magnifier(painting!!)
        }

        painting!!.setOnTouchListener(
            OnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> if (sampling) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            magnifier!!.show(event.x, event.y)
                        }
                        colorButtonDrawable!!.setWellColor(
                            painting!!.sampleAt(event.x, event.y)
                        )
                        return@OnTouchListener true
                    }

                    MotionEvent.ACTION_CANCEL -> if (sampling) {
                        findViewById<View>(R.id.btnSample).isSelected = false
                        sampling = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            magnifier!!.dismiss()
                        }
                        return@OnTouchListener true // intercept
                    }

                    MotionEvent.ACTION_UP -> if (sampling) {
                        findViewById<View>(R.id.btnSample).isSelected = false
                        sampling = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            magnifier!!.dismiss()
                        }
                        painting!!.setPaintColor(
                            painting!!.sampleAt(event.x, event.y)
                        )
                        refreshBrushAndColor()
                        return@OnTouchListener true // intercept
                    }
                }
                false // allow view to continue handling
            })

        findViewById<View>(R.id.btnBrush).setOnClickListener(buttonHandler)
        findViewById<View>(R.id.btnColor).setOnClickListener(buttonHandler)
        findViewById<View>(R.id.btnClear).setOnClickListener(buttonHandler)
        findViewById<View>(R.id.btnSample).setOnClickListener(buttonHandler)
        findViewById<View>(R.id.btnZen).setOnClickListener(buttonHandler)

        findViewById<View>(R.id.btnColor).setOnLongClickListener {
            colors?.removeAllViews()
            showToolbar(colors)
            refreshBrushAndColor()
            true
        }

        findViewById<View>(R.id.btnClear).setOnLongClickListener {
            painting!!.invertContents()
            true
        }

        widthButtonDrawable = BrushPropertyDrawable(this)
        widthButtonDrawable!!.setFrameColor(
            ContextCompat.getColor(
                this,
                R.color.p_toolbar_icon_color
            )
        )
        colorButtonDrawable = BrushPropertyDrawable(this)
        colorButtonDrawable!!.setFrameColor(
            ContextCompat.getColor(
                this,
                R.color.p_toolbar_icon_color
            )
        )

        (findViewById<View>(R.id.btnBrush) as ImageButton).setImageDrawable(widthButtonDrawable)
        (findViewById<View>(R.id.btnColor) as ImageButton).setImageDrawable(colorButtonDrawable)

        refreshBrushAndColor()
    }

    private fun refreshBrushAndColor() {
        val button_lp = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        button_lp.weight = 1f
        if (brushes!!.childCount == 0) {
            for (i in 0 until NUM_BRUSHES) {
                val icon = BrushPropertyDrawable(this)
                icon.setFrameColor(ContextCompat.getColor(this, R.color.p_toolbar_icon_color))
                // exponentially increasing brush size
                val width = lerp(
                    (i.toFloat() / NUM_BRUSHES).pow(2.0f), minBrushWidth,
                    maxBrushWidth
                )
                icon.setWellScale(width / maxBrushWidth)
                icon.setWellColor(ContextCompat.getColor(this, R.color.p_toolbar_icon_color))
                val button = ImageButton(this)
                button.setImageDrawable(icon)
                button.background = ContextCompat.getDrawable(this, R.drawable.p_toolbar_button_bg)
                button.setOnClickListener {
                    brushes!!.isSelected = false
                    hideToolbar(brushes)
                    painting!!.setBrushWidth(width)
                    refreshBrushAndColor()
                }
                brushes!!.addView(button, button_lp)
            }
        }

        if (colors!!.childCount == 0) {
            val pal = Palette(NUM_COLORS)
            val colors = pal.colors
            val allColor = IntArray(colors.size + 2)
            //System.arraycopy(colors, 0, allColor, 2, colors.length);
            allColor[0] = Color.BLACK
            allColor[1] = Color.WHITE
            allColor[2] = Color.BLUE
            allColor[3] = Color.RED
            allColor[4] = Color.GREEN
            allColor[5] = Color.YELLOW
            allColor[6] = Color.MAGENTA
            allColor[7] = Color.GRAY
//            allColor[8] = Color.CYAN
//            allColor[9] = Color.LTGRAY
//            allColor[10] = Color.DKGRAY
            for (c in allColor) {
                val icon = BrushPropertyDrawable(this)
                icon.setFrameColor(ContextCompat.getColor(this, R.color.p_toolbar_icon_color))
                icon.setWellColor(c)
                val button = ImageButton(this)
                button.setImageDrawable(icon)
                button.background = ContextCompat.getDrawable(this, R.drawable.p_toolbar_button_bg)
                button.setOnClickListener {
                    this@PaintActivity.colors!!.isSelected = false
                    hideToolbar(this@PaintActivity.colors)
                    painting!!.setPaintColor(c)
                    refreshBrushAndColor()
                }
                this.colors!!.addView(button, button_lp)
            }
            val icon = BrushPropertyDrawable(this)
            icon.setFrameColor(ContextCompat.getColor(this, R.color.p_toolbar_icon_color))
            val button = ImageButton(this)
            button.setImageDrawable(icon)
            button.background = ContextCompat.getDrawable(this, R.drawable.p_toolbar_button_bg)
            button.setOnClickListener {
                this@PaintActivity.colors!!.isSelected = false
                hideToolbar(this@PaintActivity.colors)
                var c = showColorPickerDialog()
                if (c != null) {
                    painting!!.setPaintColor(c)
                }
                refreshBrushAndColor()
        }
            this.colors!!.addView(button, button_lp)

        }

        widthButtonDrawable!!.setWellScale(painting!!.getBrushWidth() / maxBrushWidth)
        widthButtonDrawable!!.setWellColor(painting!!.getPaintColor())
        colorButtonDrawable!!.setWellColor(painting!!.getPaintColor())
    }

    private fun showColorPickerDialog(): Int? {
        var selColor: Int? = null

        val colors = listOf(
            Color.CYAN, Color.rgb(179, 157, 219), Color.MAGENTA, Color.rgb(245, 245, 220), Color.YELLOW,
            Color.rgb(169, 169, 169), Color.GREEN, Color.rgb(244, 164, 96), Color.BLUE, Color.RED,
            Color.rgb(255, 228, 181), Color.rgb(72, 61, 139), Color.rgb(205, 92, 92), Color.rgb(255, 165, 0), Color.rgb(102, 205, 170)
        )

        val numColumns = 5 // Desired number of columns
        val padding = dpToPx(15) // Convert 15 dp to pixels
        val spacing = dpToPx(15) // Set the spacing between items in dp

        val recyclerView = RecyclerView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            layoutManager = GridLayoutManager(this@PaintActivity, numColumns)
            setPadding(padding, dpToPx(20), padding, padding) // Convert padding to pixels
            adapter = ColorAdapter(this@PaintActivity, colors) { selectedColor ->
                // Do something with the selected color
                painting?.setPaintColor(selectedColor)
                colorPickerDialog.dismiss()
                selColor = selectedColor
            }
            addItemDecoration(GridSpacingItemDecoration(numColumns, spacing, true))
        }

        colorPickerDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Choose a color")
            .setView(recyclerView)
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
        colorPickerDialog.show()
        return selColor
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun refreshNightMode(config: Configuration) {
        val newNightMode =
            (config.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        if (nightMode != newNightMode) {
            if (nightMode != Configuration.UI_MODE_NIGHT_UNDEFINED) {
                painting!!.invertContents()

                (painting!!.parent as ViewGroup).removeView(painting)
                setupViews(painting)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val decorView = window.decorView
                    val decorSUIV = decorView.systemUiVisibility

                    if (newNightMode == Configuration.UI_MODE_NIGHT_YES) {
                        decorView.systemUiVisibility =
                            decorSUIV and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                    } else {
                        decorView.systemUiVisibility =
                            decorSUIV or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    }
                }
            }
            nightMode = newNightMode
        }
    }

    //    public PaintActivity() {
    //
    //    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        painting!!.onTrimMemory()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        refreshNightMode(newConfig)
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val lp = window.attributes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }
        // add safely space
//        lp.flags = lp.flags
//                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
//                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        window.attributes = lp

        maxBrushWidth = MAX_BRUSH_WIDTH_DP * resources.displayMetrics.density
        minBrushWidth = MIN_BRUSH_WIDTH_DP * resources.displayMetrics.density

        setupViews(null)
        refreshNightMode(resources.configuration)
        val aboutBtn: ImageButton = findViewById(R.id.btnAbout)
        val saveBtn: ImageButton = findViewById(R.id.btnSave)
        aboutBtn.setOnClickListener {
            val builder = MaterialAlertDialogBuilder(this@PaintActivity)
            builder
                .setTitle("About")
                .setMessage("jpb Paint, version 1.5\nBased on the Android 9 \"Pie\" Easter egg, PAINT.APK\nLicensed under the Apache License, version 2.0")
                .setPositiveButton("OK") { dialog, which ->
                    // Do something.
                }
            val dialog: AlertDialog = builder.create()
            dialog.show()
        }
        saveBtn.setOnClickListener{
            painting!!.isDrawingCacheEnabled = true
            painting!!.invalidate()
            val path = Environment.DIRECTORY_PICTURES
            val fos: OutputStream
            var imageBitmap: Bitmap
            val outputStream: OutputStream
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = this@PaintActivity.contentResolver
                val contentValues = ContentValues()
                contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, "Image_" + ".jpg")
                contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                //contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_PICTURES + File.separator+"TestFolder");
                val imageUri =
                    resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                try {
                    outputStream = Objects.requireNonNull(imageUri)
                        ?.let { resolver.openOutputStream(it) }!!
                    Objects.requireNonNull(painting!!.bitmap)
                        ?.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    Objects.requireNonNull(outputStream)
                    Toast.makeText(this@PaintActivity, "Image Saved", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@PaintActivity,
                        "Image Not Not  Saved: \n $e",
                        Toast.LENGTH_SHORT
                    ).show()
                    e.printStackTrace()
                }
            } else {
                val imagesDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        .toString()
                val image = File(imagesDir, "drawing.jpg")
                try {
                    fos = FileOutputStream(image)
                } catch (e: FileNotFoundException) {
                    throw RuntimeException(e)
                }
                checkNotNull(fos)
                painting!!.drawingCache
                    .compress(Bitmap.CompressFormat.JPEG, 100, fos)
                try {
                    Objects.requireNonNull<OutputStream>(fos).close()
                } catch (e: IOException) {
                    throw RuntimeException(e)
                }
            }
        }
    } //    @Override
    //    public void onPostResume() {
    //        super.onPostResume();
    //    }

    companion object {
        private const val MAX_BRUSH_WIDTH_DP = 100f
        private const val MIN_BRUSH_WIDTH_DP = 1f

        private const val NUM_BRUSHES = 6
        private const val NUM_COLORS = 6

        fun lerp(f: Float, a: Float, b: Float): Float {
            return a + (b - a) * f
        }
    }
}