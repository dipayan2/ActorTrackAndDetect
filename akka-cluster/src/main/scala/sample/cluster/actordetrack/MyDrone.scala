package sample.cluster.actordetrack
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.receptionist.Receptionist


import scala.concurrent.duration._

object DroneNode{
    trait Event
    case object Start extends Event
    
    def apply(): Behavior[Event] = 
        Behaviors.setup{ ctx =>
            // Need to spawn the sensors
            ctx.system.receptionist ! Receptionist.Register(App.DroneServiceKey, ctx.self)
            Behaviors.receiveMessage{
                case Start =>
                    ctx.log.info("[DRONE]Started the node")
                    Behaviors.same
            }

        }



}