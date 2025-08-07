package com.example

import org.apache.commons.math3.linear.ArrayRealVector
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import scala.util.Random
import scala.io.Source._

trait TargetD extends SensorEvent
case object TargetAck extends TargetD
case class TargetData(data: Matrix2x2, sender: ActorRef[SensorEvent]) extends TargetD
case class KalmanEstimate(data: Matrix2x2) extends TargetD
// Measurement(data: Double, sender: ActorRef[SensorEvent])
// case object 

object TargetNode{

    // A target will be created based on the measurements of the sensor from the. Maybe we can keep track of the targets created 
    def apply(id: Int, dID:Int, parent: ActorRef[Event]): Behavior[SensorEvent] = Behaviors.setup{ context =>
        val tid = id
        val parentDroneID = dID
        val parentAddr = parent
        val estimator = context.spawn(KalmanFilterActor(),"estimator")
        var state = Matrix2x2(0,0)


        context.log.info(s" Target ${tid} has been created and assigned to drone ${parentDroneID} ")
        // val estimator = context.spawn(KalmanEstimator(tid,context.self),"estimator")
        Behaviors.receiveMessage {
            case TargetData(data, sender) =>
                context.log.info(s" Target ${tid} - D${parentDroneID} actor received the measurement")
                // update state, maybe kalman state
                estimator ! Observe(data,1.0,context.self)
                /**
                 * We should send the updated state of the target, to the drone. Or just the effective ID
                */
                // sender ! TargetAck
                Behaviors.same
            
            case KalmanEstimate(data) =>
                context.log.info(s" Target ${tid} - D ${parentDroneID}expected next position is ${data.x} and ${data.y}")
                Behaviors.same
        }

    }

 
}