package freya

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import freya.internal.crd.AnyCrList
import freya.internal.kubeapi.CrdApi
import freya.internal.kubeapi.CrdApi.StatusUpdate
import freya.json.circe._
import freya.models.Metadata
import freya.resource.CirceCodecs
import freya.watcher.AnyCustomResource
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.{CustomResourceDefinition, CustomResourceDefinitionBuilder}
import io.fabric8.kubernetes.api.model.{HasMetadata, ObjectMetaBuilder}
import io.fabric8.kubernetes.client._
import io.fabric8.kubernetes.client.dsl.{NonNamespaceOperation, Resource}
import io.fabric8.kubernetes.client.utils.Serialization
import io.fabric8.kubernetes.internal.KubernetesDeserializer
import org.scalatest.DoNotDiscover
import org.scalatest.flatspec.AnyFlatSpec

import scala.jdk.CollectionConverters.MapHasAsScala

@DoNotDiscover
class StatusUpdateTest extends AnyFlatSpec with CirceCodecs {

  private[freya] def createWatch(
    kerbClient: NonNamespaceOperation[
      AnyCustomResource,
      AnyCrList,      
      Resource[AnyCustomResource]
    ]
  ): Watch = {
    kerbClient.watch(new Watcher[AnyCustomResource]() {
      override def eventReceived(action: Watcher.Action, resource: AnyCustomResource): Unit =
        println(s"received: $action for $resource")

      override def onClose(cause: WatcherException): Unit =
        println(s"watch is closed, $cause")
    })
  }

  it should "update status" in {
    Serialization.jsonMapper().registerModule(DefaultScalaModule)
    Serialization.jsonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    val prefix = "io.github.novakov-alexey"
    val version = "v1"
    val apiVersion = prefix + "/" + version
    val kind = classOf[Kerb].getSimpleName
    val plural = "kerbs"

    KubernetesDeserializer.registerCustomKind(apiVersion, kind, classOf[AnyCustomResource])
    KubernetesDeserializer.registerCustomKind(apiVersion, s"${kind}List", classOf[CustomResourceList[_ <: HasMetadata]])

    val crd = new CustomResourceDefinitionBuilder()
      .withApiVersion("apiextensions.k8s.io/v1beta1")
      .withNewMetadata
      .withName(plural + "." + prefix)
      .endMetadata
      .withNewSpec
      .withNewNames
      .withKind(kind)
      .withPlural(plural)
      .endNames
      .withGroup(prefix)
      .withVersion(version)
      .withScope("Namespaced")
      .withPreserveUnknownFields(false)
      .endSpec()
      .build()

    val kerb = Kerb("test.realm", Nil, failInTest = true)
    val anyCr = newCr(crd, kerb)

    val client = new DefaultKubernetesClient()

    val kerbClient = client
      .customResources(crd, classOf[AnyCustomResource], classOf[AnyCrList])
      .inNamespace("test")
    val watch = createWatch(kerbClient)

    kerbClient.delete(anyCr)

    val cr = kerbClient.createOrReplace(anyCr)

    val crdApi = new CrdApi(client, crd)
    crdApi.updateStatus(
      StatusUpdate(
        Metadata(
          "test-kerb",
          "test",
          cr.getMetadata.getLabels.asScala.toMap,
          cr.getMetadata.getResourceVersion,
          cr.getMetadata.getUid
        ),
        Status(true)
      )
    )

    watch.close()
  }

  private def newCr(crd: CustomResourceDefinition, spec: Kerb) = {
    val anyCr = new AnyCustomResource
    anyCr.setKind(crd.getSpec.getNames.getKind)
    anyCr.setApiVersion(s"${crd.getSpec.getGroup}/${crd.getSpec.getVersion}")
    anyCr.setMetadata(
      new ObjectMetaBuilder()
        .withName("test-kerb")
        .build()
    )
    anyCr.setSpec(Serialization.jsonMapper().writeValueAsString(spec))
    anyCr
  }
}
