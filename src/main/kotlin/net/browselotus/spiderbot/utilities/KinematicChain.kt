package net.browselotus.spiderbot.utilities

import net.browselotus.spiderbot.utilities.maths.rotate
import org.bukkit.util.Vector
import org.joml.Matrix3f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.sin


class KinematicChain(
    val root: Vector,
    val segments: List<ChainSegment>
) {
    var maxIterations = 20
    var tolerance = 0.01

    fun fabrik(target: Vector) {
        for (i in 0 until maxIterations) {
            fabrikForward(target)
            fabrikBackward()

            if (getEndEffector().distanceSquared(target) < tolerance) {
                break
            }
        }
    }

    fun straightenDirection(rotation: Quaternionf) {
        val position = root.clone()
        for (segment in segments) {
            val initDirection = segment.initDirection.clone().rotate(rotation)
            position.add(initDirection.multiply(segment.length))
            segment.position.copy(position)
        }
    }

    /**
     * Lays the chain out along the straight line from the root to [target], with every joint pushed
     * sideways towards [bend] (a world-space direction), most in the middle. Running [fabrik] from this
     * seed keeps the knee/elbow poking out towards [bend]. This is how humanoid legs get forward-facing
     * knees: the usual [straightenDirection] seed flips the knee sideways when the foot is directly
     * below the hip.
     *
     * [amount] is the sideways push as a fraction of the chain's total length.
     */
    fun seedBent(target: Vector, bend: Vector, amount: Double) {
        val toTarget = target.clone().subtract(root)
        if (toTarget.lengthSquared() < 1e-9) return
        val direction = toTarget.normalize()

        // only the part of the bend direction that is perpendicular to the chain can bend it
        val side = bend.clone().subtract(direction.clone().multiply(bend.dot(direction)))
        if (side.lengthSquared() < 1e-9) return
        side.normalize()

        val totalLength = segments.sumOf { it.length }
        var travelled = 0.0
        for (segment in segments) {
            travelled += segment.length
            val push = sin(PI * travelled / totalLength) * amount * totalLength
            segment.position.copy(
                root.clone()
                    .add(direction.clone().multiply(travelled))
                    .add(side.clone().multiply(push))
            )
        }
    }

    fun fabrikForward(newPosition: Vector) {
        val lastSegment = segments.last()
        lastSegment.position.copy(newPosition)

        for (i in segments.size - 1 downTo 1) {
            val previousSegment = segments[i]
            val segment = segments[i - 1]

            moveSegment(segment.position, previousSegment.position, previousSegment.length)
        }
    }

    fun fabrikBackward() {
        moveSegment(segments.first().position, root, segments.first().length)

        for (i in 1 until segments.size) {
            val previousSegment = segments[i - 1]
            val segment = segments[i]

            moveSegment(segment.position, previousSegment.position, segment.length)
        }
    }

    fun moveSegment(point: Vector, pullTowards: Vector, segment: Double) {
        val direction = pullTowards.clone().subtract(point).normalize()
        point.copy(pullTowards).subtract(direction.multiply(segment))
    }

    fun getEndEffector(): Vector {
        return segments.last().position
    }

    fun getVectors(): List<Vector> {
        return segments.mapIndexed { i, segment ->
            val previous = segments.getOrNull(i - 1)?.position ?: root
            segment.position.clone().subtract(previous)
        }
    }

    fun getRelativeRotations(pivot: Quaternionf): List<Quaternionf> {
        val vectors = getVectors()

        val firstEuler = vectors.first().getRotationAroundAxis(pivot)
        val firstRotation = Quaternionf(pivot).rotateYXZ(firstEuler.y, firstEuler.x, .0f)

        val rotations = vectors.mapIndexed { i, current ->
            val previous = vectors.getOrNull(i - 1) ?: return@mapIndexed firstRotation

            Quaternionf().rotationTo(previous.toVector3f(), current.toVector3f())
        }

        return rotations
    }

    fun getRotations(pivot: Quaternionf): List<Quaternionf> {
        return getRelativeRotations(pivot).apply { cumulateRotations(this) }
    }

    /**
     * Like [getRotations], but every segment's local Y axis is kept pointing as close as possible to
     * [facing] (world space), instead of being derived from yaw/pitch angles. That makes the result
     * stable for segments pointing straight down, which is where the yaw/pitch decomposition used by
     * [getRotations] hits gimbal lock and models would flip around. Meant for upright limbs.
     */
    fun getRotationsFacing(facing: Vector3f): List<Quaternionf> {
        val vectors = getVectors()

        val rotations = vectors.mapIndexed { i, current ->
            val previous = vectors.getOrNull(i - 1)
                ?: return@mapIndexed rotationAlong(current.toVector3f(), facing)

            Quaternionf().rotationTo(previous.toVector3f(), current.toVector3f())
        }

        return rotations.apply { cumulateRotations(this) }
    }

    /** A rotation taking local +Z onto [axis] and local +Y as close to [facing] as possible. */
    private fun rotationAlong(axis: Vector3f, facing: Vector3f): Quaternionf {
        val z = Vector3f(axis).normalize()

        val y = Vector3f(facing).sub(Vector3f(z).mul(facing.dot(z)))
        if (y.lengthSquared() < 1e-8f) {
            // facing is parallel to the axis, so try straight up, then sideways
            y.set(0f, 1f, 0f).sub(Vector3f(z).mul(z.y))
            if (y.lengthSquared() < 1e-8f) y.set(1f, 0f, 0f).sub(Vector3f(z).mul(z.x))
        }
        y.normalize()

        val x = Vector3f(y).cross(z).normalize()

        return Quaternionf().setFromNormalized(
            Matrix3f().setColumn(0, x).setColumn(1, y).setColumn(2, z)
        )
    }

    private fun cumulateRotations(rotations: List<Quaternionf>) {
        for (i in 1 until rotations.size) {
            rotations[i].mul(rotations[i - 1])
        }
    }
}

class ChainSegment(
    var position: Vector,
    var length: Double,
    var initDirection: Vector,
) {
    fun clone(): ChainSegment {
        return ChainSegment(position.clone(), length, initDirection.clone())
    }
}
