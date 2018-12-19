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

package quasar.physical
package blobstore
package azure

import slamdata.Predef._
import quasar.api.datasource.DatasourceType
import quasar.api.resource.ResourcePath
import quasar.blobstore.{BlobstoreStatus, ResourceType}
import quasar.blobstore.azure.{converters => _, _}
import quasar.blobstore.paths.{BlobPath, PrefixPath}
import quasar.blobstore.services.{GetService, ListService, PropsService}
import quasar.connector.MonadResourceErr
import quasar.connector.ParsableType.JsonVariant

import cats.Monad
import cats.data.Kleisli
import cats.effect.ConcurrentEffect
import cats.syntax.functor._
import com.microsoft.azure.storage.blob.models.BlobGetPropertiesResponse
import eu.timepit.refined.auto._

class AzureDatasource[
  F[_]: Monad: MonadResourceErr](
  resourcePathToBlobPath: Kleisli[F, ResourcePath, BlobPath],
  resourcePathToPrefixPath: Kleisli[F, ResourcePath, PrefixPath],
  status: F[BlobstoreStatus],
  prefixPathList: ListService[F],
  blobPathProps: PropsService[F, BlobGetPropertiesResponse],
  blobPathGet: GetService[F],
  jsonVariant: JsonVariant)
  extends BlobstoreDatasource[F, BlobGetPropertiesResponse](
    AzureDatasource.dsType,
    jsonVariant,
    resourcePathToBlobPath,
    resourcePathToPrefixPath,
    status,
    prefixPathList,
    blobPathProps,
    blobPathGet)

object AzureDatasource {
  val dsType: DatasourceType = DatasourceType("azure", 1L)

  def mk[F[_]: ConcurrentEffect: MonadResourceErr](cfg: AzureConfig): F[AzureDatasource[F]] =
    Azure.mkContainerUrl[F](cfg) map { c =>

      new AzureDatasource[F](
        converters.resourcePathToBlobPathK[F],
        converters.resourcePathToPrefixPathK[F],
        AzureStatusService.mk(c),
        AzureListService.mk[F](c),
        AzurePropsService.mk[F](c) mapF
          handlers.recoverStorageException[F, Option[BlobGetPropertiesResponse]] map
          (_.flatten),
        AzureGetService.mk(c, cfg.maxQueueSize.getOrElse(MaxQueueSize.default)),
        toJsonVariant(cfg.resourceType))
    }

  private def toJsonVariant(resourceType: ResourceType): JsonVariant =
    resourceType match {
      case ResourceType.Json => JsonVariant.ArrayWrapped
      case ResourceType.LdJson => JsonVariant.LineDelimited
    }
}
