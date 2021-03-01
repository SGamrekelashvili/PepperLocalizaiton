package ge.gis.tbcbank

import android.util.Log
import com.aldebaran.qi.sdk.`object`.streamablebuffer.StreamableBuffer
import com.aldebaran.qi.sdk.`object`.streamablebuffer.StreamableBufferFactory.fromFunction
import com.aldebaran.qi.sdk.util.copyToStream
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.*
import java.nio.ByteBuffer
import java.util.*

class SaveFileHelper {


    private val TAG = "MSI_SaveFileHelper"

    fun getLocationsFromFile(
        filesDirectoryPath: String,
        LocationsFileName: String
    ): MutableMap<String?, Vector2theta?>? {
        var vectors: MutableMap<String?, Vector2theta?>? = null
        var fis: FileInputStream? = null
        var ois: ObjectInputStream? = null
        var f: File? = null
        try {
            f = File(filesDirectoryPath, LocationsFileName)
            fis = FileInputStream(f)
            ois = ObjectInputStream(fis)
            val points = ois.readObject() as String
            val collectionType = object : TypeToken<Map<String?, Vector2theta?>?>() {}.type
            val gson = Gson()
            vectors = gson.fromJson(points, collectionType)
        } catch (e: IOException) {
            Log.e(TAG, e.message, e)
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, e.message, e)
        } finally {
            try {
                ois?.close()
                fis?.close()
            } catch (e: IOException) {
                Log.e(TAG, e.message, e)
            }
        }
        return vectors
    }


    fun saveLocationsToFile(
        filesDirectoryPath: String?,
        locationsFileName: String?,
        locationsToBackup: TreeMap<String, Vector2theta>
    ) {
        val gson = Gson()
        val points: String = gson.toJson(locationsToBackup)
        var fos: FileOutputStream? = null
        var oos: ObjectOutputStream? = null
        Log.d("TAGgg1", "backupLocations: $filesDirectoryPath")
        Log.d("TAGgg2", "backupLocations: $locationsFileName")
        Log.d("TAGgg3", "backupLocations: $locationsToBackup")

        // Backup list into a file
        try {
            val fileDirectory = File(filesDirectoryPath, "")
            if (!fileDirectory.exists()) {
                fileDirectory.mkdirs()
            }
            val file = File(fileDirectory, locationsFileName!!)
            fos = FileOutputStream(file)
            oos = ObjectOutputStream(fos)
            oos.writeObject(points)
            Log.d(TAG, "backupLocations: Done")
        } catch (e: FileNotFoundException) {
            Log.d(TAG, e.message, e)
        } catch (e: IOException) {
            Log.d(TAG, e.message, e)
        } finally {
            try {
                if (oos != null) {
                    oos.close()
                }
                if (fos != null) {
                    fos.close()
                }
            } catch (e: IOException) {
                Log.d(TAG, e.message, e)
            }
        }
    }


    fun writeStreamableBufferToFile(
        filesDirectoryPath: String?,
        fileName: String?,
        data: StreamableBuffer
    ) {
        var fos: FileOutputStream? = null
        try {
            Log.d(TAG, "writeMapDataToFile: started")
            val fileDirectory = File(filesDirectoryPath, "")
            if (!fileDirectory.exists()) {
                fileDirectory.mkdirs()
            }
            val file = File(fileDirectory, fileName!!)
            fos = FileOutputStream(file)
            data.copyToStream(fos)
        } catch (e: IOException) {
            Log.e("Exception", "File write failed: " + e.message, e)
        } finally {
            if (fos != null) {
                try {
                    fos.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
        Log.d(TAG, "writeMapDataToFile:  Finished")
    }


    fun readStreamableBufferFromFile(
        filesDirectoryPath: String?,
        fileName: String?
    ): StreamableBuffer? {
        var data: StreamableBuffer? = null
        var f: File? = null
        try {
            f = File(filesDirectoryPath, fileName!!)
            if (f.length() == 0L) return null
            data = fromFile(f)
            return data
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun fromFile(file: File): StreamableBuffer? {
        return fromFunction(file.length()) { offset: Long?, size: Long ->
            try {

                Log.d("tryyy", "fromFile: ")
                RandomAccessFile(file, "r").use { randomAccessFile ->
                    val byteArray = ByteArray(size.toInt())
                    randomAccessFile.seek(offset!!)
                    randomAccessFile.read(byteArray)
                    return@fromFunction ByteBuffer.wrap(byteArray)
                }
            } catch (e: FileNotFoundException) {
                Log.d("chatchhhh", "fromFile: ")

                val byteArray = ByteArray(size.toInt())
                return@fromFunction ByteBuffer.wrap(byteArray)
            }
        }
    }
}