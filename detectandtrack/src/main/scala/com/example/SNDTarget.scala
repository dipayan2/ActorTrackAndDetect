package com.example

import org.apache.commons.math3.linear.ArrayRealVector
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import scala.util.Random
import scala.io.Source._

trait TargetD extends SensorEvent
case class TargetAck(id:Int, ObsX:Double, ObsY: Double) extends TargetD
case class TargetDataObs(id: Int, data: Matrix2x2, sender: ActorRef[SensorEvent]) extends TargetD
case class KalmanEstimate(dataPos: Matrix2x2, dataVel: Matrix2x2) extends TargetD
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
        var isinitilized = false
        val thresh = 0.5


        context.log.info(s" Target ${tid} has been created and assigned to drone ${parentDroneID} ")
        // val estimator = context.spawn(KalmanEstimator(tid,context.self),"estimator")
        Behaviors.receiveMessage {
            case TargetDataObs(id, data, sender) =>
                context.log.info(s" Target ${tid} - D${parentDroneID} actor received the measurement")
                if (!isinitilized) {
                    state = data
                    isinitilized = true
                }

                val dist = TargetNode.calculateDistance(state, data)

                if(dist < thresh) {
                    // update state, maybe kalman state
                    estimator ! Observe(data,1.0,context.self)
                    /**
                     * We should send the updated state of the target, to the drone. Or just the effective ID
                    */
                } else {
                    context.log.info(s" Target ${tid} - D${parentDroneID} actor received a measurement that is too far away from the current state. Ignoring it.")
                }
                // sender ! TargetAck
                Behaviors.same

            case KalmanEstimate(dataPos, dataVel) =>
                context.log.info(s" Target ${tid} - D ${parentDroneID} expected next position is ${dataPos.x} and ${dataPos.y}")
                state = dataPos
                parentAddr ! TargetAck(tid, dataPos.x, dataPos.y)
                Behaviors.same
        }




    }
        
        def calculateDistance(pos1: Matrix2x2, pos2: Matrix2x2): Double = {
            math.sqrt(math.pow(pos1.x - pos2.x, 2) + math.pow(pos1.y - pos2.y, 2))
        }


 
}