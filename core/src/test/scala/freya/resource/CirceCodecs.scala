package freya.resource

import io.circe.Decoder
import io.circe.Encoder
import freya.Kerb
import freya.Status
import freya.Principal
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

trait CirceCodecs {
  implicit lazy val principalDecoder: Decoder[Principal] = deriveDecoder[Principal]
  implicit lazy val principalEncoder: Encoder[Principal] = deriveEncoder[Principal]
  implicit lazy val kerbDecoder: Decoder[Kerb] = deriveDecoder[Kerb]
  implicit lazy val kerbEncoder: Encoder[Kerb] = deriveEncoder[Kerb]
  implicit lazy val statusDecoder: Decoder[Status] = deriveDecoder[Status]
  implicit lazy val statusEncoder: Encoder[Status] = deriveEncoder[Status]

}
