/*
 * Copyright 2014–2018 SlamData Inc.
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

package quasar.physical.blobstore.azure

import slamdata.Predef._
import quasar.Disposable
import quasar.api.datasource.DatasourceError.InitializationError
import quasar.api.datasource.{DatasourceError, DatasourceType}
import quasar.blobstore.azure._
import json._
import quasar.blobstore.BlobstoreStatus
import quasar.connector.{LightweightDatasourceModule, MonadResourceErr}

import java.net.{MalformedURLException, UnknownHostException}
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import argonaut.Json
import argonaut.ArgonautScalaz._
import cats.{Applicative, ApplicativeError}
import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import scalaz.{NonEmptyList, \/}
import scalaz.syntax.either._
import scalaz.syntax.equal._

object AzureDatasourceModule extends LightweightDatasourceModule {

  private val redactedCreds =
    AzureCredentials(
      AccountName("<REDACTED>"),
      AccountKey("<REDACTED>"))

  override def kind: DatasourceType = AzureDatasource.dsType

  @SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
  override def lightweightDatasource[F[_]: ConcurrentEffect: ContextShift: MonadResourceErr: Timer](
      json: Json)(
      implicit ec: ExecutionContext)
      : F[InitializationError[Json] \/ Disposable[F, DS[F]]] =
    json.as[AzureConfig].result match {
      case Right(cfg) =>
        val r = for {
          ds <- AzureDatasource.mk(cfg)
          l <- ds.status
          res = l match {
            case BlobstoreStatus.Ok => Disposable(ds.asDsType, Applicative[F].unit).right
            case BlobstoreStatus.NoAccess =>
              DatasourceError
                .accessDenied[Json, InitializationError[Json]](kind, json, "Access to blobstore denied")
                .left
            case BlobstoreStatus.NotFound =>
              DatasourceError
                .invalidConfiguration[Json, InitializationError[Json]](kind, json, NonEmptyList("Blobstore not found"))
                .left
            case BlobstoreStatus.NotOk(msg) =>
              DatasourceError
                .invalidConfiguration[Json, InitializationError[Json]](kind, json, NonEmptyList(msg))
                .left
          }
        } yield res

        ApplicativeError[F, Throwable].handleError(r) {
          case _: MalformedURLException =>
            DatasourceError
              .invalidConfiguration[Json, InitializationError[Json]](kind, json, NonEmptyList("Invalid storage url"))
              .left
          case _: UnknownHostException =>
            DatasourceError
              .invalidConfiguration[Json, InitializationError[Json]](kind, json, NonEmptyList("Non-existing storage url"))
              .left
          case NonFatal(t) =>
            DatasourceError
              .invalidConfiguration[Json, InitializationError[Json]](kind, json, NonEmptyList(t.getMessage))
              .left
        }

      case Left((msg, _)) =>
        DatasourceError
          .invalidConfiguration[Json, InitializationError[Json]](kind, json, NonEmptyList(msg))
          .left.pure[F]

    }

  override def sanitizeConfig(config: Json): Json = {
    val sanitized = for {
      creds <- config.cursor --\ "credentials"

      redacted =
        if (creds.focus === Json.jNull) creds
        else creds.set(json.codecCredentials.encode(redactedCreds))
    } yield redacted.undo
    sanitized.getOrElse(config)
  }
}
