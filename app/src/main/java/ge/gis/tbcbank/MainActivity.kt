package ge.gis.tbcbank

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.aldebaran.qi.Consumer
import com.aldebaran.qi.Future
import com.aldebaran.qi.Promise
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.QiSDK
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks
import com.aldebaran.qi.sdk.`object`.actuation.*
import com.aldebaran.qi.sdk.`object`.geometry.TransformTime
import com.aldebaran.qi.sdk.`object`.holder.AutonomousAbilitiesType
import com.aldebaran.qi.sdk.`object`.holder.Holder
import com.aldebaran.qi.sdk.`object`.streamablebuffer.StreamableBuffer
import com.aldebaran.qi.sdk.builder.ExplorationMapBuilder
import com.aldebaran.qi.sdk.builder.HolderBuilder
import com.aldebaran.qi.sdk.builder.LocalizeAndMapBuilder
import com.aldebaran.qi.sdk.design.activity.RobotActivity
import com.aldebaran.qi.sdk.util.FutureUtils
import com.softbankrobotics.dx.pepperextras.actuation.StubbornGoTo
import com.softbankrobotics.dx.pepperextras.actuation.StubbornGoToBuilder
import com.softbankrobotics.dx.pepperextras.util.SingleThread
import com.softbankrobotics.dx.pepperextras.util.asyncFuture
import com.softbankrobotics.dx.pepperextras.util.await
import com.softbankrobotics.dx.pepperextras.util.awaitOrNull
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayList
import kotlin.concurrent.thread


class MainActivity : RobotActivity(), RobotLifecycleCallbacks {
    private var nextLocation: String? = null
    var goToRandomRunning = false
    private var goToRandomFuture: Future<Void>? = null
    private var streamableExplorationMap: StreamableBuffer? = null
    private var toSaveExplorationMap: ExplorationMap? = null

    private var TAG = "DDDD"
    private lateinit var spinnerAdapter: ArrayAdapter<String>
    private var selectedLocation: String? = null
    private var savedLocations = mutableMapOf<String, AttachedFrame>()
    private var qiContext: QiContext? = null
    private var actuation: Actuation? = null
    private var mapping: Mapping? = null
    private var initialExplorationMap: ExplorationMap? = null
    private var holder: Holder? = null
    var publishExplorationMapFuture: Future<Void>? = null
    var goto: StubbornGoTo? = null
    private val MULTIPLE_PERMISSIONS = 2
    private val RECHARGE_PERMISSION =
        "com.softbankrobotics.permission.AUTO_RECHARGE" // recharge permission
    var saveFileHelper: SaveFileHelper? = null
    val filesDirectoryPath = "/sdcard/Maps"
    val mapFileName = "mapData.txt"
    private val locationsFileName = "points.json"
    private val load_location_success =
        AtomicBoolean(false)

    private var awd: MutableMap<String, AttachedFrame> = mutableMapOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        QiSDK.register(this, this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        saveFileHelper = SaveFileHelper()
        if (ContextCompat.checkSelfPermission(
                this,
                RECHARGE_PERMISSION
            ) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            QiSDK.register(this, this)
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    RECHARGE_PERMISSION
                ),
                MULTIPLE_PERMISSIONS
            )
        }

        save_button.setOnClickListener {
            val location: String = add_item_edit.text.toString()
            add_item_edit.text.clear()
            // Save location only if new.
            if (location.isNotEmpty() && !savedLocations.containsKey(location)) {
                spinnerAdapter.add(location)
                saveLocation(location)


            }
        }


        goToRandom.setOnClickListener {
            goToRandomLocation(true)
        }

        loadMap.setOnClickListener {

            if (getStreamableMap() == null) {
                val mapData = saveFileHelper!!.readStreamableBufferFromFile(
                    filesDirectoryPath,
                   mapFileName
                )


                setStreamableMap(mapData!!)

                Thread{


                val readedExplorationMap: ExplorationMap = ExplorationMapBuilder.with(qiContext).withStreamableBuffer(streamableExplorationMap).build()

                    mapToBitmap(readedExplorationMap)


                }.start()





            }

        }

        get.setOnClickListener {

            loadLocations()

        }

        goto_button.setOnClickListener {
            selectedLocation?.let {
                goto_button.isEnabled = false
                save_button.isEnabled = false
                val thread = Thread {
                    goToLocation(it, OrientationPolicy.ALIGN_X)
                }
                thread.start()
            }
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View,
                position: Int,
                id: Long
            ) {
                selectedLocation = parent.getItemAtPosition(position) as String
                Log.i("TAG", "onItemSelected: $selectedLocation")
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                selectedLocation = null
                Log.i("TAG", "onNothingSelected")
            }
        }

        spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ArrayList())
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = spinnerAdapter


        loadLocations()



        sTopLocalization.setOnClickListener {


            publishExplorationMapFuture?.cancel(true)
            releaseAbilities(holder!!)
        }

        extendMapButton.setOnClickListener {
            // Check that an initial map is available.
            val initialExplorationMap = initialExplorationMap ?: return@setOnClickListener
            // Check that the Activity owns the focus.
            val qiContext = qiContext ?: return@setOnClickListener
            // Start the map extension step.
            startMapExtensionStep(initialExplorationMap, qiContext)
        }

        startMappingButton.setOnClickListener {
            // Check that the Activity owns the focus.
            val qiContext = qiContext ?: return@setOnClickListener
            // Start the mapping step.
            startMappingStep(qiContext)
        }

    }


    fun createAttachedFrameFromCurrentPosition(): Future<AttachedFrame>? {
        return actuation!!.async()
            .robotFrame()
            .andThenApply { robotFrame: Frame ->
                val mapFrame: Frame = mapping!!.async().mapFrame().value;
//                val newFreeFrame: FreeFrame = mapping!!.makeFreeFrame()
//
//                val transform: Transform = TransformBuilder.create().fromXTranslation(0.0)
//                newFreeFrame.update(robotFrame, transform, 0L)

                val transformTime: TransformTime = robotFrame.computeTransform(mapFrame)
                mapFrame.makeAttachedFrame(transformTime.transform)
            }
    }

    fun saveLocation(location: String?) {
        // Get the robot frame asynchronously.
        Log.d(TAG, "saveLocation: Start saving this location")



        createAttachedFrameFromCurrentPosition()?.andThenConsume {


                attachedFrame ->
            awd[location!!] = attachedFrame

            backupLocations()

        }
    }

    private fun getMapFrame(): Frame? {
        return mapping!!.async().mapFrame().value
    }

    private fun backupLocations() {

        val locationsToBackup = TreeMap<String, Vector2theta>()
        val mapFrame: Frame = getMapFrame()!!
        for ((key, destination) in awd) {
            // get location of the frame
            Log.d(
                "sdsdsdsd", destination.toString()
            )
            val frame = destination.async().frame().value

            // create a serializable vector2theta
            val vector = Vector2theta.betweenFrames(mapFrame, frame)

            // add to backup list
            locationsToBackup[key] = vector
        }
        SaveFileHelper().saveLocationsToFile(
            filesDirectoryPath,
            locationsFileName,
            locationsToBackup
        )

    }

    fun loadLocations(): Future<Boolean>? {
        return FutureUtils.futureOf<Boolean> { f: Future<Void?>? ->
            val file =
                File(filesDirectoryPath, locationsFileName)
            if (file.exists()) {
                val vectors: MutableMap<String?, Vector2theta?>? =
                    saveFileHelper!!.getLocationsFromFile(
                        filesDirectoryPath,
                        locationsFileName
                    )

                // Clear current savedLocations.
                awd = TreeMap()
                val mapFrame: Frame? =
                    getMapFrame()


                Log.i("yvelaa", vectors.toString())

                vectors!!.forEach { (key1, value1) ->

                    val t = value1!!.createTransform()
                    Log.d(TAG, "loadLocations: $key1")

                    runOnUiThread {
                        spinnerAdapter.add(key1)
                    }



                    val attachedFrame =
                        mapFrame!!.async().makeAttachedFrame(t).value

                    // Store the FreeFrame.
                    awd[key1!!] = attachedFrame

                }

                load_location_success.set(true)


                runOnUiThread {

                    Toast.makeText(this, awd.toString(), Toast.LENGTH_LONG).show()
                }

                Log.d(TAG, "loadLocations: Done")
                Log.d(TAG, awd.toString())
                if (load_location_success.get()) return@futureOf Future.of(
                    true
                ) else throw Exception("Empty file")
            } else {
                throw Exception("No file")
            }
        }
    }


    fun goToRandomLocation(setGoToRandom: Boolean) {
        goToRandomRunning = setGoToRandom
        if (goToRandomRunning) {
            goToRandomFuture = FutureUtils.wait(15, TimeUnit.SECONDS)
                .andThenConsume { aUselessFuture: Void? ->
                    goToRandomLocation(
                        goToRandomRunning
                    )
                }
            nextLocation = pickRandomLocation()
            thread {

                goToLocation(nextLocation!!, OrientationPolicy.ALIGN_X)
            }.start()

        } else {
            try {
                goToRandomFuture!!.thenConsume(
                    Consumer<Future<Void?>> { voidFuture: Future<Void?>? ->
                        Log.d(
                            TAG,
                            "goToRandomFuture: cancelled"
                        )
                    }
                )
                goToRandomFuture!!.cancel(true)
            } catch (e: Exception) {
                Log.d(TAG, "goToRandomLocation: error: $e")
            }
        }
    }


    private fun pickRandomLocation(): String {
        val keysAsArray: MutableList<String> = java.util.ArrayList(awd.keys)
        val r = Random()
        val location = keysAsArray[r.nextInt(keysAsArray.size)]
        return if (location != nextLocation) {
            location
        } else pickRandomLocation()
    }

    private fun goToLocation(location: String, orientationPolicy: OrientationPolicy) {

        Log.d("awdawdawd", location)

        val freeFrame: AttachedFrame? = awd[location]
        val frameFuture: Frame = freeFrame!!.frame()
        val appscope = SingleThread.newCoroutineScope()
        val future: Future<Boolean> = appscope.asyncFuture {
            goto = StubbornGoToBuilder.with(qiContext!!)
                .withFinalOrientationPolicy(orientationPolicy)
                .withMaxRetry(10)
                .withMaxSpeed(0.5f)
                .withMaxDistanceFromTargetFrame(0.3)
                .withWalkingAnimationEnabled(true)
                .withFrame(frameFuture).build()
            goto!!.async().run().await()
        }
        runBlocking {
            delay(5000)
            future.requestCancellation()
            future.awaitOrNull()
            delay(5000)
            goto!!.async().run().await()
        }
        waitForInstructions()
    }


    fun waitForInstructions() {
        Log.i("TAG", "Waiting for instructions...")
        runOnUiThread {
            save_button.isEnabled = true
            goto_button.isEnabled = true
        }
    }


    override fun onRobotFocusGained(qiContext: QiContext?) {

        this.qiContext = qiContext
        actuation = qiContext!!.actuation
        mapping = qiContext.mapping
        runOnUiThread {
            startMappingButton.isEnabled = true
        }
    }

    override fun onResume() {
        super.onResume()
        // Reset UI and variables state.
        startMappingButton.isEnabled = false
        extendMapButton.isEnabled = false
        initialExplorationMap = null
    }

    override fun onRobotFocusLost() {
        qiContext = null

    }


    override fun onRobotFocusRefused(reason: String?) {

    }
    fun setStreamableMap(map: StreamableBuffer) {
        streamableExplorationMap = map
    }
    private fun mapSurroundings(qiContext: QiContext): Future<ExplorationMap> {
        // Create a Promise to set the operation state later.
        val promise = Promise<ExplorationMap>().apply {
            // If something tries to cancel the associated Future, do cancel it.
            setOnCancel {
                if (!it.future.isDone) {
                    setCancelled()
                }
            }
        }

        // Create a LocalizeAndMap, run it, and keep the Future.
        val localizeAndMapFuture = LocalizeAndMapBuilder.with(qiContext)
            .buildAsync()
            .andThenCompose { localizeAndMap ->
                // Add an OnStatusChangedListener to know when the robot is localized.
                localizeAndMap.addOnStatusChangedListener { status ->
                    if (status == LocalizationStatus.LOCALIZED) {
                        // Retrieve the map.
                        val explorationMap = localizeAndMap.dumpMap()

                        toSaveExplorationMap = explorationMap

                        // Set the Promise state in success, with the ExplorationMap.
                        if (!promise.future.isDone) {
                            promise.setValue(explorationMap)
                        }
                    }
                }

                // Run the LocalizeAndMap.
                localizeAndMap.async().run()

                    .thenConsume {
                        // Remove the OnStatusChangedListener.
                        localizeAndMap.removeAllOnStatusChangedListeners()
                        // In case of error, forward it to the Promise.
                        if (it.hasError() && !promise.future.isDone) {
                            promise.setError(it.errorMessage)
                        }
                    }
            }

        // Return the Future associated to the Promise.
        return promise.future.thenCompose {
            // Stop the LocalizeAndMap.
            localizeAndMapFuture.cancel(true)
            return@thenCompose it
        }
    }

    private fun mapToBitmap(explorationMap: ExplorationMap) {
        explorationMapView.setExplorationMap(explorationMap.topGraphicalRepresentation)
    }
    fun getStreamableMap(): StreamableBuffer? {
        return streamableExplorationMap
    }

    private fun startMappingStep(qiContext: QiContext) {
        Log.i(TAG.toString(), "startMappingStep Class")
        // Map the surroundings and get the map.
        mapSurroundings(qiContext).thenConsume { future ->
            if (future.isSuccess) {
                Log.i(TAG, "FUTURE Success")
                val explorationMap = future.get()
                // Store the initial map.
                this.initialExplorationMap = explorationMap
                // Convert the map to a bitmap.
                mapToBitmap(explorationMap)
                // Display the bitmap and enable "extend map" button.

                setStreamableMap(toSaveExplorationMap!!.serializeAsStreamableBuffer())

                val mapData: StreamableBuffer? = getStreamableMap()
                saveFileHelper!!.writeStreamableBufferToFile(
                    filesDirectoryPath,
                    mapFileName,
                    mapData!!
                )
                runOnUiThread {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        extendMapButton.isEnabled = true
                    }
                }
            } else {
                // If the operation is not a success, re-enable "start mapping" button.
                runOnUiThread {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        startMappingButton.isEnabled = true
                    }
                }
            }
        }
    }

    private fun publishExplorationMap(
        localizeAndMap: LocalizeAndMap,
        updatedMapCallback: (ExplorationMap) -> Unit
    ): Future<Void> {
        return localizeAndMap.async().dumpMap().andThenCompose {
            Log.i(TAG, "$it Function")
            updatedMapCallback(it)
            FutureUtils.wait(1, TimeUnit.SECONDS)
        }.andThenCompose {
            publishExplorationMap(localizeAndMap, updatedMapCallback)
        }
    }

    private fun startMapExtensionStep(initialExplorationMap: ExplorationMap, qiContext: QiContext) {
        Log.i(TAG.toString(), "StartMapEXTENSION Class")
        extendMapButton.isEnabled = false
        holdAbilities(qiContext)
        extendMap(initialExplorationMap, qiContext) { updatedMap ->
            explorationMapView.setExplorationMap(initialExplorationMap.topGraphicalRepresentation)
            mapToBitmap(updatedMap)
            // Display the bitmap.
            runOnUiThread {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    robotOnMap(initialExplorationMap, qiContext)
                }
            }
        }.thenConsume { future ->
            // If the operation is not a success, re-enable "extend map" button.
            if (!future.isSuccess) {
                runOnUiThread {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        extendMapButton.isEnabled = true
                    }
                }
            }
        }
    }


    private fun robotOnMap(initialExplorationMap: ExplorationMap, qiContext: QiContext) {
        Log.i(TAG.toString(), "$initialExplorationMap Class")

    }

    private fun holdAbilities(qiContext: QiContext) {
        // Build and store the holder for the abilities.
        holder = HolderBuilder.with(qiContext)
            .withAutonomousAbilities(
                AutonomousAbilitiesType.BACKGROUND_MOVEMENT,
                AutonomousAbilitiesType.BASIC_AWARENESS,
                AutonomousAbilitiesType.AUTONOMOUS_BLINKING
            )
            .build()

        // Hold the abilities asynchronously.
        val holdFuture: Future<Void> = holder!!.async().hold()
    }

    private fun releaseAbilities(holder: Holder) {
        // Release the holder asynchronously.
        val releaseFuture: Future<Void> = holder.async().release()
    }

    private fun extendMap(
        explorationMap: ExplorationMap,
        qiContext: QiContext,
        updatedMapCallback: (ExplorationMap) -> Unit
    ): Future<Void> {
        Log.i(TAG.toString(), "ExtandMap Class")
        val promise = Promise<Void>().apply {
            // If something tries to cancel the associated Future, do cancel it.
            setOnCancel {
                if (!it.future.isDone) {
                    setCancelled()
                }
            }
        }

        // Create a LocalizeAndMap with the initial map, run it, and keep the Future.
        val localizeAndMapFuture = LocalizeAndMapBuilder.with(qiContext)
            .withMap(explorationMap)
            .buildAsync()
            .andThenCompose { localizeAndMap ->
                Log.i(TAG.toString(), "localizeandmap Class")

                // Add an OnStatusChangedListener to know when the robot is localized.
                localizeAndMap.addOnStatusChangedListener { status ->
                    if (status == LocalizationStatus.LOCALIZED) {
                        // Start the map notification process.
                        publishExplorationMapFuture =
                            publishExplorationMap(localizeAndMap, updatedMapCallback)
                    }
                }

                // Run the LocalizeAndMap.
                localizeAndMap.async().run()
                    .thenConsume {
                        // Remove the OnStatusChangedListener.
                        localizeAndMap.removeAllOnStatusChangedListeners()
                        // Stop the map notification process.
                        publishExplorationMapFuture?.cancel(true)
                        // In case of error, forward it to the Promise.
                        if (it.hasError() && !promise.future.isDone) {
                            promise.setError(it.errorMessage)
                        }
                    }
            }

        // Return the Future associated to the Promise.
        return promise.future.thenCompose {
            // Stop the LocalizeAndMap.
            localizeAndMapFuture.cancel(true)
            return@thenCompose it
        }
    }

}









