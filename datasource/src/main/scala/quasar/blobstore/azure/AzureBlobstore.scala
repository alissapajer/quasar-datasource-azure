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

package quasar.blobstore.azure

import slamdata.Predef._
import quasar.api.resource.{ResourceName, ResourcePath, ResourcePathType}
import quasar.blobstore.azure.requests.ListBlobHierarchyArgs
import quasar.blobstore.{Blobstore, BlobstoreStatus, Converter, ops}
import quasar.connector.{MonadResourceErr, ResourceError}

import java.lang.Integer
import scala.collection.JavaConverters._

import cats.effect._
import cats.implicits._
import com.microsoft.azure.storage.blob._
import com.microsoft.azure.storage.blob.models._
import com.microsoft.rest.v2.Context
import fs2.{RaiseThrowable, Stream}

class AzureBlobstore[F[_]: ConcurrentEffect: MonadResourceErr: RaiseThrowable](
  containerURL: ContainerURL,
  maxQueueSize: MaxQueueSize) extends Blobstore[F] {

  private val F = ConcurrentEffect[F]

  implicit val resourcePathToBlobURL: Converter[F, ResourcePath, BlobURL] =
    new Converter[F, ResourcePath, BlobURL] {
      override def convert(a: ResourcePath): F[BlobURL] = F.delay(pathToBlobUrl(a))
    }

  implicit val propsResponseToBoolean: Converter[F, BlobGetPropertiesResponse, Boolean] =
    new Converter[F, BlobGetPropertiesResponse, Boolean]{
      override def convert(a: BlobGetPropertiesResponse): F[Boolean] = true.pure[F]
    }

  private def errorHandler[A](path: ResourcePath): Throwable => Stream[F, A] = {
    case ex: StorageException if ex.statusCode() === 404 =>
      Stream.raiseError(ResourceError.throwableP(ResourceError.pathNotFound(path)))
  }

  private val statusService = AzureStatusService(containerURL)
  private val getService = AzureGetService(maxQueueSize, errorHandler)
  private val propsService = AzurePropsService[F, ResourcePath, Boolean](
    _.recover { case _: StorageException => false })

  def get(path: ResourcePath): Stream[F, Byte] = getService.get(path)

  def isResource(path: ResourcePath): F[Boolean] = propsService.props(path)

  def status: F[BlobstoreStatus] = statusService.status

  def list(path: ResourcePath): F[Option[Stream[F, (ResourceName, ResourcePathType)]]] =
    ops.service[F, ResourcePath, ListBlobHierarchyArgs, ContainerListBlobHierarchySegmentResponse, Option[Stream[F, (ResourceName, ResourcePathType)]], F[Option[Stream[F, (ResourceName, ResourcePathType)]]]](
      p => ListBlobHierarchyArgs(containerURL, None, "/", pathToOptions(p), Context.NONE).pure[F],
      requests.listRequest[F],
      toResourceNamesAndTypes(_, path).pure[F],
      x => x
    ).apply(path)

  private def toResourceNamesAndTypes(r: ContainerListBlobHierarchySegmentResponse, path: ResourcePath)
      : Option[Stream[F, (ResourceName, ResourcePathType)]] = {
    Option(r.body.segment).map { segm =>
      val l = segm.blobItems.asScala.map(blobItemToNameType(_, path)) ++
        segm.blobPrefixes.asScala.map(blobPrefixToNameType(_, path))
      Stream.emits(l).covary[F]
    }
  }

  private def blobItemToNameType(i: BlobItem, path: ResourcePath): (ResourceName, ResourcePathType) =
    (ResourceName(simpleName(i.name)), ResourcePathType.LeafResource)

  private def blobPrefixToNameType(p: BlobPrefix, path: ResourcePath): (ResourceName, ResourcePathType) =
    (ResourceName(simpleName(p.name)), ResourcePathType.Prefix)

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def normalize(name: String): String =
    if (name.endsWith("/")) normalize(name.substring(0, name.length - 1))
    else name

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def normalizePrefix(name: String): String =
    if (name.endsWith("//")) normalizePrefix(name.substring(0, name.length - 1))
    else name

  private def pathToAzurePath(path: ResourcePath): String = {
    val names = ResourcePath.resourceNamesIso.get(path).map(_.value).toList
    names.mkString("/")
  }

  private def pathToBlobUrl(path: ResourcePath): BlobURL =
    containerURL.createBlobURL(pathToAzurePath(path))

  private def pathToOptions(path: ResourcePath): ListBlobsOptions =
    new ListBlobsOptions()
      .withMaxResults(Integer.valueOf(5000))
      .withPrefix(normalizePrefix(pathToPrefix(path)))

  private def pathToPrefix(path: ResourcePath): String = {
    val names = ResourcePath.resourceNamesIso.get(path).map(_.value).toList
    val s = names.mkString("", "/", "/")
    if (s === "/") "" else s
  }

  private def simpleName(s: String): String = {
    val ns = normalize(s)
    ns.substring(ns.lastIndexOf('/') + 1)
  }
}
