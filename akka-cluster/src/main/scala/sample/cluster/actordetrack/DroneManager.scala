package sample.cluster.actordetrack
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.receptionist.Receptionist


object DroneManager{
    
    def apply():Behavior[Receptionist.Listing] ={
        Behaviors.setup[Receptionist.Listing]{ ctx=>
            ctx.log.info("[DRONE]Manager Started")
            // ctx.system.receptionist ! Receptionist.Subscribe(App.DroneServiceKey, ctx.self)
            ctx.system.receptionist ! Receptionist.Subscribe(DroneNode.DroneServiceKey, ctx.self)
            Behaviors.receiveMessagePartial[Receptionist.Listing]{
            case DroneNode.DroneServiceKey.Listing(listings) =>
              listings.foreach{ps => 
                ps ! DroneNode.Start
                ctx.log.info("[DRONE] New Drone Started")
              }
              Behaviors.same
          }

        }
    }
}