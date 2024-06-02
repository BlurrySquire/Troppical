package io.github.troppical.dialogs

import android.content.Context
import android.os.Bundle
import android.app.Activity
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.*
import com.bumptech.glide.Glide
import io.github.troppical.R
import io.github.troppical.network.GitHubReleaseFetcher
import io.github.troppical.network.APKDownloader
import io.github.troppical.network.APKInstaller
import io.github.troppical.utils.ZipExtractor
import java.io.File

class EmulatorAboutDialog(context: Context, private val activity: Activity, private val item: HashMap<String, Any>) : BaseSheetDialog(context) {

    private val fetcherScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var downloadUrl: String
    private var apkPath: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.emulator_about_dialog)

        val emulatorName = findViewById<TextView>(R.id.emulator_name)
        val emulatorDesc = findViewById<TextView>(R.id.emulator_desc)
        val emulatorLogo = findViewById<ImageView>(R.id.emulator_logo)
        val emulatorLatestVersion = findViewById<TextView>(R.id.emulator_latest_version)
        val installButton = findViewById<MaterialButton>(R.id.install)

        emulatorName.text = item["emulator_name"].toString()
        emulatorDesc.text = item["emulator_desc"].toString()
        Glide.with(context).load(Uri.parse(item["emulator_logo"].toString())).into(emulatorLogo)

        fetcherScope.launch {
            val fetcher = GitHubReleaseFetcher(item["emulator_owner"].toString(), item["emulator_repo"].toString())
            try {
                val artifactName = item["emulator_artifact_name"].toString()
                val (directLink, tagName) = fetcher.fetchArtifactDirectLinkAndTag(artifactName)
                emulatorLatestVersion.text = tagName

                if (directLink != null) {
                    downloadUrl = directLink 
                    Log.w("EmulatorAboutDialog", "Direct download link is $directLink")
                } else {
                    Log.e("EmulatorAboutDialog", "Artifact not found")
                }
            } catch (e: Exception) {
                Log.e("EmulatorAboutDialog", "Error fetching version", e)
                emulatorLatestVersion.text = "Error fetching version"
            } finally {
                fetcher.close()
            }
        }
        
        installButton.setOnClickListener {
           install()
        }                 
    }

    private fun install() {
        val outputFile = File(context.filesDir, item["emulator_artifact_name"].toString())
        val downloader = APKDownloader(downloadUrl, outputFile)

        val progressDialog = MaterialAlertDialogBuilder(context)
            .setTitle("Downloading")
            .setView(R.layout.progress_dialog)
            .setCancelable(false)
            .create()

        val progressIndicator: LinearProgressIndicator

        progressDialog.show()
        progressIndicator = progressDialog.findViewById(R.id.progress_indicator)!!

        downloader.download(
            onProgress = { progress ->
                activity.runOnUiThread {
                    progressIndicator.progress = progress
                }
            },
            onComplete = { success ->
                activity.runOnUiThread {
                    progressDialog.dismiss()
                    if (success) {
                        if (outputFile.extension.equals("apk", ignoreCase = true)) {
                            val installer = APKInstaller(context)
                            installer.install(outputFile)
                            apkPath = outputFile
                        } else {
                            val progressDialogExtract = MaterialAlertDialogBuilder(context)
                                .setTitle("Extracting")
                                .setView(R.layout.progress_dialog)
                                .setCancelable(false)
                                .create()

                            progressDialogExtract.show()
                            val progressIndicatorExtract = progressDialogExtract.findViewById<LinearProgressIndicator>(R.id.progress_indicator)!!

                            val zipExtractor = ZipExtractor(
                                zipFilePath = outputFile, 
                                destDirectory = context.filesDir,
                                progressCallback = { progress ->
                                    activity.runOnUiThread {
                                        progressIndicatorExtract.progress = progress
                                    }
                                },
                                onComplete = { success, apkFilePath ->
                                    outputFile.delete()
                                    apkPath = apkFilePath
                                    activity.runOnUiThread {
                                        progressDialogExtract.dismiss()
                                        if (success && apkFilePath != null) {
                                            val installer = APKInstaller(context)
                                            apkFilePath?.let { installer.install(it) }
                                        } else {
                                            // TODO: Handle extraction failure
                                            Log.e("EmulatorAboutDialog", "Extraction failed or no APK file found.")
                                        }
                                    }
                                }
                            )

                            CoroutineScope(Dispatchers.IO).launch {
                                zipExtractor.extract()
                            }
                        }      
                    } else {
                        // TODO: Handle download failure
                        Log.e("EmulatorAboutDialog", "Download failed.")
                    }
                }
            }
        )
    }

    override fun onStop() {
        super.onStop()
        apkPath?.let {
            if (it.exists()) {
                it.delete() // Ensure that the apk is deleted when no longer needed
            }
        }
        fetcherScope.cancel() // Cancel any ongoing coroutines when the dialog is destroyed
    }
}