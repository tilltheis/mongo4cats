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

import cats.syntax.apply._
import mongo4cats.bson.json._
import mongo4cats.bson.{BsonValue, Document, MongoJsonParsingException, ObjectId}
import zio.json.ast.Json

import java.time.{Instant, LocalDate, ZoneOffset}
import scala.math.BigDecimal._

private[json] object ZioJsonMapper extends JsonMapper[Json] {

  def toBson(json: Json): BsonValue =
    json match {
      case j if j.isNull        => BsonValue.Null
      case j if j.isArray       => BsonValue.array(j.asArray.get.map(toBson))
      case j if j.isBoolean     => BsonValue.boolean(j.asBoolean.get)
      case j if j.isString      => BsonValue.string(j.asString.get)
      case j if j.isNumber      => j.asNumber.get.toBsonValue
      case j if j.isId          => BsonValue.objectId(j.asObjectId)
      case j if j.isEpochMillis => BsonValue.instant(Instant.ofEpochMilli(j.asEpochMillis))
      case j if j.isLocalDate   => BsonValue.instant(LocalDate.parse(j.asIsoDateString).atStartOfDay().toInstant(ZoneOffset.UTC))
      case j if j.isDate        => BsonValue.instant(Instant.parse(j.asIsoDateString))
      case j => BsonValue.document(Document(j.asObject.get.fields.toList.map { case (key, value) => key -> toBson(value) }))
    }

  implicit final private class JsonSyntax(private val json: Json) extends AnyVal {
    def isNull: Boolean        = json.asNull.nonEmpty
    def isArray: Boolean       = json.asArray.nonEmpty
    def isBoolean: Boolean     = json.asBoolean.nonEmpty
    def isString: Boolean      = json.asString.nonEmpty
    def isNumber: Boolean      = json.asNumber.nonEmpty
    def isId: Boolean          = json.asObject.nonEmpty && json.asObject.exists(_.contains(Tag.id))
    def isDate: Boolean        = json.asObject.nonEmpty && json.asObject.exists(_.contains(Tag.date))
    def isEpochMillis: Boolean = isDate && json.asObject.exists(_.get(Tag.date).exists(_.isNumber))
    def isLocalDate: Boolean =
      isDate && json.asObject.exists(o => o.get(Tag.date).exists(_.isString) && o.get(Tag.date).exists(_.asString.get.length == 10))

    def asEpochMillis: Long =
      (for {
        obj  <- json.asObject
        date <- obj.get(Tag.date)
        num  <- date.asNumber
        ts = num.value.toLong
      } yield ts).get

    def asIsoDateString: String =
      (for {
        obj  <- json.asObject
        date <- obj.get(Tag.date)
        str  <- date.asString
      } yield str).get

    def asObjectId: ObjectId =
      (for {
        obj   <- json.asObject
        id    <- obj.get(Tag.id)
        hex   <- id.asString
        objId <- ObjectId.from(hex).toOption
      } yield objId).get
  }

  implicit final private class JsonNumSyntax(private val jNumber: Json.Num) extends AnyVal {
    def isDecimal: Boolean = jNumber.toString.contains(".")
    def toBsonValue: BsonValue =
      (isDecimal, jNumber.value) match {
        case (true, n)                   => BsonValue.bigDecimal(n)
        case (false, n) if n.isValidInt  => BsonValue.int(n.toInt)
        case (false, n) if n.isValidLong => BsonValue.long(n.toLong)
        case (_, n)                      => BsonValue.bigDecimal(n)
      }
  }

  def fromBson(bson: BsonValue): Either[MongoJsonParsingException, Json] = {
    def rightEmptyList[A]: Either[MongoJsonParsingException, List[A]] = Right(List.empty[A])

    bson match {
      case BsonValue.BNull            => Right(Json.Null)
      case BsonValue.BObjectId(value) => Right(Json.Obj(Tag.id -> Json.Str(value.toHexString)))
      case BsonValue.BDateTime(value) => Right(Json.Obj(Tag.date -> Json.Str(value.toString)))
      case BsonValue.BInt32(value)    => Right(Json.Num(value))
      case BsonValue.BInt64(value)    => Right(Json.Num(value))
      case BsonValue.BBoolean(value)  => Right(Json.Bool(value))
      case BsonValue.BDecimal(value)  => Right(Json.Num(value))
      case BsonValue.BString(value)   => Right(Json.Str(value))
      case BsonValue.BDouble(value)   => Right(Json.Num(value))
      case BsonValue.BArray(value) =>
        value.toList
          .foldRight(rightEmptyList[Json]) { case (a, acc) => (fromBson(a), acc).mapN(_ :: _) }
          .map(xs => Json.Arr(xs: _*))
      case BsonValue.BDocument(value) =>
        value.toList
          .filterNot { case (_, v) => v.isUndefined }
          .foldRight(rightEmptyList[(String, Json)]) { case (a, acc) => (fromBson(a._2), acc).mapN((x, xs) => (a._1, x) :: xs) }
          .map(xs => Json.Obj(xs: _*))
      case value => Left(MongoJsonParsingException(s"Cannot map $value bson value to json"))
    }
  }

  def fromBsonOpt(bson: BsonValue): Option[Json] =
    bson match {
      case BsonValue.BNull            => Some(Json.Null)
      case BsonValue.BObjectId(value) => Some(Json.Obj(Tag.id -> Json.Str(value.toHexString)))
      case BsonValue.BDateTime(value) => Some(Json.Obj(Tag.date -> Json.Str(value.toString)))
      case BsonValue.BInt32(value)    => Some(Json.Num(value))
      case BsonValue.BInt64(value)    => Some(Json.Num(value))
      case BsonValue.BBoolean(value)  => Some(Json.Bool(value))
      case BsonValue.BDecimal(value)  => Some(Json.Num(value))
      case BsonValue.BString(value)   => Some(Json.Str(value))
      case BsonValue.BDouble(value)   => Some(Json.Num(value))
      case BsonValue.BArray(value)    => Some(Json.Arr(value.toList.flatMap(fromBsonOpt): _*))
      case BsonValue.BDocument(value) => Some(Json.Obj(value.toList.flatMap { case (k, v) => fromBsonOpt(v).map(k -> _) }: _*))
      case _                          => None
    }
}
