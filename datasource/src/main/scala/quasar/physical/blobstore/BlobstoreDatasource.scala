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

package quasar.physical.blobstore

import slamdata.Predef._
import quasar.api.datasource.DatasourceType
import quasar.api.resource.{ResourceName, ResourcePath, ResourcePathType}
import quasar.blobstore.BlobstoreStatus
import quasar.connector._
import ParsableType.JsonVariant
import quasar.blobstore.paths.{BlobPath, PrefixPath}
import quasar.blobstore.services.{GetService, ListService, PropsService}
import quasar.connector.datasource.LightweightDatasource
import quasar.contrib.scalaz.MonadError_

import cats.Monad
import cats.data.Kleisli
import cats.effect.IO
import cats.syntax.applicative._
import cats.syntax.functor._
import cats.syntax.flatMap._
import fs2.Stream

class BlobstoreDatasource[F[_]: Monad: MonadResourceErr, P](
  val kind: DatasourceType,
  jvar: JsonVariant,
  resourcePathToBlobPath: Kleisli[F, ResourcePath, BlobPath],
  resourcePathToPrefixPath: Kleisli[F, ResourcePath, PrefixPath],
  blobstoreStatus: F[BlobstoreStatus],
  listService: ListService[F],
  propsService: PropsService[F, P],
  getService: GetService[F])
  extends LightweightDatasource[F, Stream[F, ?], QueryResult[F]] {

  private def raisePathNotFound(path: ResourcePath) =
    MonadResourceErr[F].raiseError(ResourceError.pathNotFound(path))

  override def evaluate(path: ResourcePath): F[QueryResult[F]] =
    for {
      blobPath <- resourcePathToBlobPath(path)
      optBytes <- getService(blobPath)
      bytes <- optBytes.map(_.pure[F]).getOrElse(raisePathNotFound(path))
      qr = QueryResult.typed[F](ParsableType.json(jvar, false), bytes)
    } yield qr

  override def pathIsResource(path: ResourcePath): F[Boolean] =
    (resourcePathToBlobPath andThen propsService map { _.isDefined }).apply(path)

  override def prefixedChildPaths(prefixPath: ResourcePath)
      : F[Option[Stream[F, (ResourceName, ResourcePathType)]]] =
    (resourcePathToPrefixPath andThen listService.map(_.map(_.map(converters.toResourceNameType)))).apply(prefixPath)

  def asDsType: Datasource[F, Stream[F, ?], ResourcePath, QueryResult[F]] = this

  def status: F[BlobstoreStatus] = blobstoreStatus
}

object BlobstoreDatasource {

  import shims._

  implicit val ioMonadResourceErr: MonadError_[IO, ResourceError] =
    MonadError_.facet[IO](ResourceError.throwableP)

}
