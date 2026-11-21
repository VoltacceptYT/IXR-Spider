package net.browselotus.spiderbot.spider.configuration

import net.browselotus.spiderbot.utilities.BlockDisplayModelPiece
import net.browselotus.spiderbot.utilities.DisplayModel
import org.bukkit.util.Vector
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * One rigid, separately moving piece of the droid (chest, head, an upper arm, a forearm).
 *
 * The [model] is authored in body space (pelvis = origin) in the rest pose. At render time it is rotated
 * by [rotation] around [pivot], and then carried along by whatever its [parent] part is doing.
 */
class RigPart(
    val name: String,
    val parent: String?,
    val pivot: Vector3f,
    var model: DisplayModel,
) {
    val rotation = Quaternionf()
}

/** Pose of one arm. All angles are in radians. */
class ArmPose {
    /** Swing of the whole arm at the shoulder, positive = forward. */
    var flex = 0f

    /** Bend of the elbow, positive = forearm comes forward/up. */
    var elbow = 0f

    /** Raising the arm out to the side, positive = outward. */
    var abduct = 0f

    /** Turning the arm left/right around the vertical axis. Positive = towards the droid's left. */
    var yaw = 0f
}

/**
 * The droid's skeleton: chest, head and both arms (upper arm + forearm) move independently of the pelvis,
 * which stays on the body, while the legs are driven by the usual leg chains.
 *
 * Hierarchy: chest -> head, upperL -> foreL, upperR -> foreR.
 *
 * Pose angles are set every tick by the droid animator (see DroidAnimator.kt), the renderer reads the
 * resulting matrices through [updateChains] / [chain].
 */
class DroidRig(
    val parts: List<RigPart>,
    /** The tip of the cannon, in the left forearm's own (body space, rest pose) coordinates. */
    val muzzleLocal: Vector3f,
) {
    companion object {
        const val CHEST = "chest"
        const val HEAD = "head"
        const val UPPER_L = "upperL"
        const val UPPER_R = "upperR"
        const val FORE_L = "foreL"
        const val FORE_R = "foreR"
    }

    // ---------- pose (radians) ----------
    /** Chest twist around the vertical axis, positive = towards the droid's left. */
    var chestYaw = 0f
    /** Chest lean, positive = forward. */
    var chestPitch = 0f
    /** Chest tilt sideways, positive = top goes towards the droid's right. */
    var chestRoll = 0f

    var headYaw = 0f
    /** Head nod, positive = looking down. */
    var headPitch = 0f
    var headRoll = 0f

    val leftArm = ArmPose()
    val rightArm = ArmPose()

    // ---------- animation state ----------
    var time = 0
    /** 0..1, how much the cannon arm is aimed at a target. */
    var aim = 0f
    /** 1 right after firing, decays to 0. */
    var recoil = 0f
    /** 1 right after being hit, decays to 0. */
    var shock = 0f

    private val chains = HashMap<String, Matrix4f>()

    fun part(name: String): RigPart? = parts.firstOrNull { it.name == name }

    /** The body-space matrix that moves [name]'s rest-pose model into its current pose. */
    fun chain(name: String): Matrix4f = chains.getOrPut(name) { Matrix4f() }

    fun allPieces(): List<BlockDisplayModelPiece> = parts.flatMap { it.model.pieces }

    fun scale(factor: Float) {
        for (part in parts) {
            part.pivot.mul(factor)
            part.model.scale(factor)
        }
        muzzleLocal.mul(factor)
    }

    private fun applyArm(upper: RigPart?, fore: RigPart?, arm: ArmPose, side: Float) {
        upper?.rotation?.identity()?.rotateY(arm.yaw)?.rotateZ(side * arm.abduct)?.rotateX(-arm.flex)
        fore?.rotation?.identity()?.rotateX(-arm.elbow)
    }

    private fun applyPose() {
        part(CHEST)?.rotation?.identity()?.rotateY(chestYaw)?.rotateX(chestPitch)?.rotateZ(chestRoll)
        part(HEAD)?.rotation?.identity()?.rotateY(headYaw)?.rotateX(headPitch)?.rotateZ(headRoll)
        applyArm(part(UPPER_L), part(FORE_L), leftArm, 1f)
        applyArm(part(UPPER_R), part(FORE_R), rightArm, -1f)
    }

    /** Recomputes every part's matrix from the current pose. Parents are listed before their children. */
    fun updateChains() {
        applyPose()
        for (part in parts) {
            val matrix = Matrix4f()
            val parentMatrix = part.parent?.let { chains[it] }
            if (parentMatrix != null) matrix.set(parentMatrix)

            matrix
                .translate(part.pivot.x, part.pivot.y, part.pivot.z)
                .rotate(part.rotation)
                .translate(-part.pivot.x, -part.pivot.y, -part.pivot.z)

            chain(part.name).set(matrix)
        }
    }

    /** Where the cannon's muzzle currently is in the world, for a body at [position] facing [orientation]. */
    fun muzzleWorld(position: Vector, orientation: Quaternionf): Vector {
        updateChains()
        val point = Vector3f(muzzleLocal)
        chain(FORE_L).transformPosition(point)
        point.rotate(orientation)
        return position.clone().add(Vector(point.x.toDouble(), point.y.toDouble(), point.z.toDouble()))
    }
}
