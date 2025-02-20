package io.cloudstate.operator.stores
import io.cloudstate.operator.{Condition, ImageConfig, OperatorConstants, StatefulStore, Validated}
import skuber.api.client.KubernetesClient
import io.cloudstate.operator.OperatorConstants._
import io.cloudstate.operator.StatefulStore.Resource
import play.api.libs.json.JsValue
import skuber.EnvVar

object CassandraStoreSupport extends StatefulStoreSupport {

  override def name: String = OperatorConstants.CassandraStatefulStoreType

  override def validate(store: StatefulStore.Resource, client: KubernetesClient): Validated[ConfiguredStatefulStore] = {
    store.spec.deployment match {
      case Some(`UnmanagedStatefulStoreDeployment`) =>
        store.spec.config.flatMap(c => (c \ "service").asOpt[String]) match {
          case Some(serviceName) =>
            Validated(new UnmanagedCassandra(serviceName))

          case None =>
            Validated.error(StatefulStoreConditionType, "MissingServiceName",
              "No service name declared in unmanaged Cassandra journal")
        }

      case Some(unknown) =>
        Validated.error(StatefulStoreConditionType, "UnknownDeploymentType",
          s"Unknown Cassandra deployment type: $unknown, supported types for Cassandra are: $UnmanagedStatefulStoreDeployment")

      case None =>
        Validated.error(StatefulStoreConditionType, "UnspecifiedDeploymentType",
          s"Unspecified Cassandra deployment type, supported types for Cassandra are: $UnmanagedStatefulStoreDeployment")

    }
  }


  override def reconcile(store: Resource, client: KubernetesClient): Validated[ConfiguredStatefulStore] = validate(store, client)

  private class UnmanagedCassandra(service: String) extends ConfiguredStatefulStore {
    override def successfulConditions: List[Condition] = Nil

    override def validateInstance(config: Option[JsValue], client: KubernetesClient): Validated[StatefulStoreUsageConfiguration] = {
      config.flatMap(config => (config \ "keyspace").asOpt[String]) match {
        case Some(keyspace) =>
          Validated(new CassandraUsage(service, keyspace))
        case None =>
          Validated.error(StatefulStoreConditionType, "MissingKeyspace",
            "No keyspace declared for unmanaged Cassandra journal")
      }
    }
  }

  private class CassandraUsage(service: String, keyspace: String) extends StatefulStoreUsageConfiguration {
    override def successfulConditions: List[Condition] = Nil
    override def proxyImage(config: ImageConfig): String = config.cassandra
    override def proxyContainerEnvVars: List[EnvVar] = List(
      EnvVar("CASSANDRA_CONTACT_POINTS", service),
      EnvVar("CASSANDRA_KEYSPACE", keyspace)
    )
  }
}
