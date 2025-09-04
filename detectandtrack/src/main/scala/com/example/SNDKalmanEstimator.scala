package com.example

import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.ArrayRealVector
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration._
import scala.collection.mutable.ListBuffer
import org.apache.commons.math3.linear.MatrixUtils
import akka.actor.typed.ActorRef
import breeze.linalg._

case class KalmanInternal(x:Double, y:Double, vx:Double, vy:Double)
case class Observe(obs: Matrix2x2, dt: Double = 1.0, replyTo: ActorRef[TargetD]) extends SensorEvent
case class KalmanState(
    x: DenseVector[Double],
    P: DenseMatrix[Double],
    zPrev: Option[DenseVector[Double]]
  )

object KalmanFilterActor{

    def apply(): Behavior[Observe] = Behaviors.receive{(context,msg) =>
        val initState = KalmanState(
            x = DenseVector(0.0, 0.0, 0.0, 0.0),
            P = getInitialCovariance(),
            zPrev = None
        )
        filtering(initState)
    }

    private def filtering(state: KalmanState): Behavior[Observe] = Behaviors.receive{(context, msg) =>
        val Matrix2x2(xMeas, yMeas) = msg.obs
        val dt = msg.dt
        val z = DenseVector(xMeas, yMeas)

        // State transition matrix (constant velocity model)
        val A = DenseMatrix(
            (1.0, 0.0, dt, 0.0),
            (0.0, 1.0, 0.0, dt),
            (0.0, 0.0, 1.0, 0.0),
            (0.0, 0.0, 0.0, 1.0)
        )

        // Observation matrix (we observe position only)
        val H = DenseMatrix(
            (1.0, 0.0, 0.0, 0.0),
            (0.0, 1.0, 0.0, 0.0)
        )

        // OPTIMIZED PROCESS NOISE MATRIX (Q)
        // Based on your target motion: max velocity 0.1, with sinusoidal acceleration
        val Q = getProcessNoiseMatrix(dt)
        
        // OPTIMIZED MEASUREMENT NOISE MATRIX (R)
        // Based on typical sensor precision for your grid resolution
        val R = getMeasurementNoiseMatrix()

        // Predict step
        val xPred = A * state.x
        val PPred = A * state.P * A.t + Q

        // Update step
        val y = z - (H * xPred)
        val S = H * PPred * H.t + R
        val K = PPred * H.t * inv(S)

        val xUpd = xPred + K * y
        val PUpd = (DenseMatrix.eye[Double](4) - K * H) * PPred

        // Send updated estimate
        msg.replyTo ! KalmanEstimate(
            dataPos = Matrix2x2(xUpd(0), xUpd(1)), 
            dataVel = Matrix2x2(xUpd(2), xUpd(3))
        )

        filtering(state.copy(x = xUpd, P = PUpd, zPrev = Some(z)))
    }

    /**
     * Optimized process noise matrix based on target motion characteristics
     */
    private def getProcessNoiseMatrix(dt: Double): DenseMatrix[Double] = {
        // Your targets have:
        // - Max velocity: 0.1 pixels/frame
        // - Max acceleration from sinusoidal motion: ~0.0008 pixels/frame²
        // - Velocity standard deviation: ~0.02 pixels/frame
        
        val positionVariance = math.pow(0.05 * dt, 2)  // Position uncertainty per time step
        val velocityVariance = math.pow(0.02, 2)        // Velocity process noise
        val accelerationVariance = math.pow(0.001, 2)   // Small acceleration changes
// // 1000x 1000
//         val positionVariance = math.pow(0.05 * dt, 2)  // Position uncertainty per time step
//         val velocityVariance = math.pow(0.02, 2)        // Velocity process noise
//         val accelerationVariance = math.pow(0.001, 2)   // Small acceleration changes
        
        // Continuous-discrete process noise model
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        val dt4 = dt3 * dt
        
        // Process noise for position-velocity model with acceleration
        val q = accelerationVariance  // Acceleration spectral density
        
        DenseMatrix(
            (q * dt4 / 4.0,     0.0,           q * dt3 / 2.0,   0.0),
            (0.0,               q * dt4 / 4.0, 0.0,             q * dt3 / 2.0),
            (q * dt3 / 2.0,     0.0,           q * dt2,         0.0),
            (0.0,               q * dt3 / 2.0, 0.0,             q * dt2)
        )
    }

    /**
     * Optimized measurement noise matrix
     */
    private def getMeasurementNoiseMatrix(): DenseMatrix[Double] = {
        // Your simulation uses discrete grid positions with potential sub-pixel precision
        // Measurement noise should reflect sensor accuracy and discretization error
        val measurementStdDev = 1.0  // ~0.5 pixel standard deviation
        val measurementVariance = measurementStdDev * measurementStdDev
        
        DenseMatrix(
            (measurementVariance, 0.0),
            (0.0, measurementVariance)
        )
    }

    /**
     * Initial covariance matrix - reflects uncertainty at target initialization
     */
    private def getInitialCovariance(): DenseMatrix[Double] = {
        // Initial uncertainty:
        // - Position: moderate (targets can start anywhere in region)  
        // - Velocity: low (velocities are constrained to ±0.06 max)
        
        val positionVariance = 100.0     // ±5 pixel initial position uncertainty
        val velocityVariance = 0.01     // ±0.1 pixel/frame velocity uncertainty
        
        DenseMatrix(
            (positionVariance,  0.0,              0.0,             0.0),
            (0.0,               positionVariance, 0.0,             0.0),
            (0.0,               0.0,              velocityVariance, 0.0),
            (0.0,               0.0,              0.0,             velocityVariance)
        )
    }

    /**
     * Alternative: Adaptive Kalman Filter with velocity-based process noise
     */
    def applyAdaptive(): Behavior[Observe] = Behaviors.receive{(context,msg) =>
        val initState = KalmanState(
            x = DenseVector(0.0, 0.0, 0.0, 0.0),
            P = getInitialCovariance(),
            zPrev = None
        )
        adaptiveFiltering(initState)
    }

    private def adaptiveFiltering(state: KalmanState): Behavior[Observe] = Behaviors.receive{(context, msg) =>
        val Matrix2x2(xMeas, yMeas) = msg.obs
        val dt = msg.dt
        val z = DenseVector(xMeas, yMeas)

        // State transition matrix
        val A = DenseMatrix(
            (1.0, 0.0, dt, 0.0),
            (0.0, 1.0, 0.0, dt),
            (0.0, 0.0, 1.0, 0.0),
            (0.0, 0.0, 0.0, 1.0)
        )

        val H = DenseMatrix(
            (1.0, 0.0, 0.0, 0.0),
            (0.0, 1.0, 0.0, 0.0)
        )

        // ADAPTIVE: Adjust process noise based on estimated velocity
        val currentVelocity = math.sqrt(state.x(2)*state.x(2) + state.x(3)*state.x(3))
        val Q = getAdaptiveProcessNoise(dt, currentVelocity)
        val R = getMeasurementNoiseMatrix()

        // Standard Kalman filter steps
        val xPred = A * state.x
        val PPred = A * state.P * A.t + Q

        val y = z - (H * xPred)
        val S = H * PPred * H.t + R
        val K = PPred * H.t * inv(S)

        val xUpd = xPred + K * y
        val PUpd = (DenseMatrix.eye[Double](4) - K * H) * PPred

        msg.replyTo ! KalmanEstimate(
            dataPos = Matrix2x2(xUpd(0), xUpd(1)), 
            dataVel = Matrix2x2(xUpd(2), xUpd(3))
        )

        adaptiveFiltering(state.copy(x = xUpd, P = PUpd, zPrev = Some(z)))
    }

    /**
     * Adaptive process noise - increases with target velocity
     */
    private def getAdaptiveProcessNoise(dt: Double, velocity: Double): DenseMatrix[Double] = {
        // Base process noise
        val baseQ = 0.0005
        
        // Increase process noise for faster targets (sinusoidal motion creates more uncertainty)
        val adaptiveFactor = 1.0 + (velocity / 0.1) * 2.0  // Scale with velocity relative to max
        val q = baseQ * adaptiveFactor
        
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        val dt4 = dt3 * dt
        
        DenseMatrix(
            (q * dt4 / 4.0,     0.0,           q * dt3 / 2.0,   0.0),
            (0.0,               q * dt4 / 4.0, 0.0,             q * dt3 / 2.0),
            (q * dt3 / 2.0,     0.0,           q * dt2,         0.0),
            (0.0,               q * dt3 / 2.0, 0.0,             q * dt2)
        )
    }
}