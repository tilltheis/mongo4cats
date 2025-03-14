/*
 * Copyright 2020 Kirill5k
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package mongo4cats.zio.json

import mongo4cats.bson.json._
import mongo4cats.bson.syntax._
import mongo4cats.bson._
import mongo4cats.codecs.MongoCodecProvider
import org.bson.codecs.configuration.CodecProvider
import zio.json.ast.Json
import zio.json.{JsonDecoder, JsonEncoder}

import java.time.{Instant, LocalDate}
import scala.reflect.ClassTag
import scala.util.Try

trait MongoJsonCodecs {
  private val emptyJsonObject = Json.Obj()

  implicit def deriveJsonBsonValueEncoder[A](implicit e: JsonEncoder[A]): BsonValueEncoder[A] =
    value => ZioJsonMapper.toBson(e.toJsonAST(value).toOption.get)

  implicit def deriveJsonBsonValueDecoder[A](implicit d: JsonDecoder[A]): BsonValueDecoder[A] =
    bson => ZioJsonMapper.fromBson(bson).flatMap(d.fromJsonAST).toOption

  implicit val documentEncoder: JsonEncoder[Document] =
    Json.encoder.contramap[Document](d => ZioJsonMapper.fromBsonOpt(BsonValue.document(d)).getOrElse(emptyJsonObject))

  implicit val documentDecoder: JsonDecoder[Document] =
    Json.decoder.mapOrFail(j => ZioJsonMapper.toBson(j).asDocument.toRight(s"$j is not a valid document"))

  implicit val objectIdEncoder: JsonEncoder[ObjectId] =
    Json.encoder.contramap[ObjectId](i => Json.Obj(Tag.id -> Json.Str(i.toHexString)))

  implicit val objectIdDecoder: JsonDecoder[ObjectId] =
    Json.decoder.mapOrFail[ObjectId] { id =>
      (for {
        obj  <- id.asObject
        json <- obj.get(Tag.id)
        str  <- json.asString
      } yield ObjectId(str)).toRight(s"$id is not a valid object id")
    }

  implicit val instantEncoder: JsonEncoder[Instant] =
    Json.encoder.contramap[Instant](i => Json.Obj(Tag.date -> Json.Str(i.toString)))

  implicit val instantDecoder: JsonDecoder[Instant] =
    Json.decoder.mapOrFail[Instant] { dateObj =>
      (for {
        obj   <- dateObj.asObject
        date  <- obj.get(Tag.date)
        tsStr <- date.asString
        ts    <- Try(Instant.parse(tsStr)).toOption
      } yield ts).toRight(s"$dateObj is not a valid instant object")
    }

  implicit val localDateEncoder: JsonEncoder[LocalDate] =
    Json.encoder.contramap[LocalDate](i => Json.Obj(Tag.date -> Json.Str(i.toString)))

  implicit val localDateDecoder: JsonDecoder[LocalDate] =
    Json.decoder.mapOrFail[LocalDate] { dateObj =>
      (for {
        obj   <- dateObj.asObject
        date  <- obj.get(Tag.date)
        ldStr <- date.asString
        ld    <- Try(LocalDate.parse(ldStr.slice(0, 10))).toOption
      } yield ld).toRight(s"$dateObj is not a valid local date object")
    }

  implicit def deriveZioJsonCodecProvider[T: ClassTag](implicit enc: JsonEncoder[T], dec: JsonDecoder[T]): MongoCodecProvider[T] =
    new MongoCodecProvider[T] {
      override def get: CodecProvider = codecProvider[T](
        _.toBson,
        ZioJsonMapper.fromBson(_).flatMap(j => dec.fromJsonAST(j).left.map(e => MongoJsonParsingException(e, Some(j.toString))))
      )
    }
}
