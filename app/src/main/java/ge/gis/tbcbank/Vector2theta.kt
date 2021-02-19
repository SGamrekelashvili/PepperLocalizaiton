package ge.gis.tbcbank

import com.aldebaran.qi.sdk.`object`.actuation.Frame
import com.aldebaran.qi.sdk.`object`.geometry.Quaternion
import com.aldebaran.qi.sdk.`object`.geometry.Transform
import com.aldebaran.qi.sdk.`object`.geometry.Vector3
import com.aldebaran.qi.sdk.builder.TransformBuilder

class Vector2theta private constructor(
    private val x: Double,
    private val y: Double,
    private val theta: Double
) {

    fun createTransform(): Transform {
        // this.theta is the radian angle to appy taht was serialized
        return TransformBuilder.create().from2DTransform(x, y, theta)
    }

    companion object {

        fun betweenFrames(frameDestination: Frame, frameOrigin: Frame): Vector2theta {
            // Compute the transform to go from "frameOrigin" to "frameDestination"
            val transform: Transform =
                frameOrigin.async().computeTransform(frameDestination).value.transform

            // Extract translation from the transform
            val translation: Vector3 = transform.translation
            // Extract quaternion from the transform
            val quaternion: Quaternion = transform.rotation

            // Extract the 2 coordinates from the translation and orientation angle from quaternion
            return Vector2theta(
                translation.x,
                translation.y,
                NavUtils().getYawFromQuaternion(quaternion)
            )
        }
    }
}