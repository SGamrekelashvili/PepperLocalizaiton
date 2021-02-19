package ge.gis.tbcbank

import com.aldebaran.qi.sdk.`object`.geometry.Quaternion
import kotlin.math.atan2

class NavUtils {


    fun getYawFromQuaternion(q: Quaternion): Double {
        // yaw (z-axis rotation)
        val x = q.x
        val y = q.y
        val z = q.z
        val w = q.w
        val sinYaw = 2.0 * (w * z + x * y)
        val cosYaw = 1.0 - 2.0 * (y * y + z * z)
        return atan2(sinYaw, cosYaw)
    }

}