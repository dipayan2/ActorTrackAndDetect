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
            P = DenseMatrix.eye[Double](4) ,
            zPrev = None
        )
        filtering(initState)
    }

    private def filtering(state: KalmanState): Behavior[Observe] = Behaviors.receive{(context, msg) =>
        val Matrix2x2(xMeas, yMeas) = msg.obs
        val dt = msg.dt
        val z = DenseVector(xMeas,yMeas)

        // Define matrices
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

        val Q = DenseMatrix.eye[Double](4) * 0.01
        val R = DenseMatrix.eye[Double](2)  * 1.0

        // Predict
        val xPred = A * state.x
        val PPred = A * state.P * A.t + Q

        // Update
        val y = z - (H * xPred)
        val S = H * PPred * H.t + R
        val K = PPred * H.t * inv(S)

        val xUpdPartial = xPred + K * y
        val PUpd = (DenseMatrix.eye[Double](4)  - K * H) * PPred

        // Estimate velocity from position difference
        val (vx, vy) = state.zPrev match {
        case Some(prevZ) =>
            val dx = z(0) - prevZ(0)
            val dy = z(1) - prevZ(1)
            (dx / dt, dy / dt)
        case None => (0.0, 0.0)
        }

        val xUpd = DenseVector(xUpdPartial(0), xUpdPartial(1), vx, vy)

        replyTo ! Matrix2x2(xUpd(0),xUpd(1))

        filtering(state.copy(x = xUpd, P = PUpd, zPrev = Some(z)))

    }

}