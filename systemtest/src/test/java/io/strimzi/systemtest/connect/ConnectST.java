/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.connect;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapKeySelectorBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.SecretKeySelectorBuilder;
import io.fabric8.kubernetes.api.model.SecretVolumeSourceBuilder;
import io.skodjob.annotations.Desc;
import io.skodjob.annotations.Label;
import io.skodjob.annotations.Step;
import io.skodjob.annotations.SuiteDoc;
import io.skodjob.annotations.TestDoc;
import io.strimzi.api.kafka.model.common.CertSecretSourceBuilder;
import io.strimzi.api.kafka.model.common.ConnectorState;
import io.strimzi.api.kafka.model.common.PasswordSecretSourceBuilder;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
import io.strimzi.api.kafka.model.connect.KafkaConnectResources;
import io.strimzi.api.kafka.model.connect.KafkaConnectStatus;
import io.strimzi.api.kafka.model.connect.build.JarArtifactBuilder;
import io.strimzi.api.kafka.model.connect.build.Plugin;
import io.strimzi.api.kafka.model.connect.build.PluginBuilder;
import io.strimzi.api.kafka.model.connector.AutoRestartBuilder;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.connector.KafkaConnectorStatus;
import io.strimzi.api.kafka.model.kafka.KafkaResources;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerAuthenticationScramSha512;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerAuthenticationTls;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerType;
import io.strimzi.api.kafka.model.user.KafkaUser;
import io.strimzi.operator.common.Annotations;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.systemtest.AbstractST;
import io.strimzi.systemtest.Environment;
import io.strimzi.systemtest.TestConstants;
import io.strimzi.systemtest.annotations.MicroShiftNotSupported;
import io.strimzi.systemtest.annotations.ParallelNamespaceTest;
import io.strimzi.systemtest.kafkaclients.internalClients.KafkaClients;
import io.strimzi.systemtest.kafkaclients.internalClients.KafkaClientsBuilder;
import io.strimzi.systemtest.resources.NodePoolsConverter;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.resources.crd.KafkaConnectResource;
import io.strimzi.systemtest.resources.crd.KafkaConnectorResource;
import io.strimzi.systemtest.resources.crd.KafkaUserResource;
import io.strimzi.systemtest.resources.kubernetes.NetworkPolicyResource;
import io.strimzi.systemtest.storage.TestStorage;
import io.strimzi.systemtest.templates.crd.KafkaConnectTemplates;
import io.strimzi.systemtest.templates.crd.KafkaConnectorTemplates;
import io.strimzi.systemtest.templates.crd.KafkaNodePoolTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTopicTemplates;
import io.strimzi.systemtest.templates.crd.KafkaUserTemplates;
import io.strimzi.systemtest.templates.specific.ScraperTemplates;
import io.strimzi.systemtest.utils.ClientUtils;
import io.strimzi.systemtest.utils.RollingUpdateUtils;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.systemtest.utils.VerificationUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaConnectUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaConnectorUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.StrimziPodSetUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.PodUtils;
import io.strimzi.test.ReadWriteUtils;
import io.strimzi.test.TestUtils;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

import static io.strimzi.systemtest.TestConstants.ACCEPTANCE;
import static io.strimzi.systemtest.TestConstants.COMPONENT_SCALING;
import static io.strimzi.systemtest.TestConstants.CONNECT;
import static io.strimzi.systemtest.TestConstants.CONNECTOR_OPERATOR;
import static io.strimzi.systemtest.TestConstants.CONNECT_COMPONENTS;
import static io.strimzi.systemtest.TestConstants.REGRESSION;
import static io.strimzi.systemtest.TestConstants.SANITY;
import static io.strimzi.systemtest.TestConstants.SMOKE;
import static io.strimzi.systemtest.enums.CustomResourceStatus.NotReady;
import static io.strimzi.systemtest.enums.CustomResourceStatus.Ready;
import static io.strimzi.test.k8s.KubeClusterResource.cmdKubeClient;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.valid4j.matchers.jsonpath.JsonPathMatchers.hasJsonPath;

@Tag(REGRESSION)
@Tag(CONNECT)
@Tag(CONNECT_COMPONENTS)
@SuppressWarnings({"checkstyle:ClassDataAbstractionCoupling"})
@SuiteDoc(
    description = @Desc("Verifies the deployment, manual rolling update, and undeployment of Kafka Connect components."),
    beforeTestSteps = {
        @Step(value = "Deploy scraper Pod for accessing all other Pods", expected = "Scraper Pod is deployed")
    },
    labels = {
        @Label(value = "connect")
    }
)
class ConnectST extends AbstractST {

    private static final Logger LOGGER = LogManager.getLogger(ConnectST.class);

    @ParallelNamespaceTest
    @TestDoc(
        description = @Desc("Verifies the deployment, manual rolling update, and undeployment of Kafka Connect components."),
        steps = {
            @Step(value = "Initialize Test Storage", expected = "Test storage instance is created with required context"),
            @Step(value = "Define expected configurations", expected = "Configurations are loaded from properties file"),
            @Step(value = "Create and wait for resources", expected = "Kafka resources, including NodePools and KafkaConnect instances, are created and become ready"),
            @Step(value = "Annotate for manual rolling update", expected = "KafkaConnect components are annotated for a manual rolling update"),
            @Step(value = "Perform and wait for rolling update", expected = "KafkaConnect components roll and new pods are deployed"),
            @Step(value = "Kafka Connect pod", expected = "Pod configurations and annotations are verified"),
            @Step(value = "Kafka Connectors", expected = "Various Kafka Connect resource labels and configurations are verified to ensure correct deployment")
        },
        labels = {
            @Label(value = "connect")
        }
    )
    void testDeployRollUndeploy() {
        final TestStorage testStorage = new TestStorage(ResourceManager.getTestContext());

        final int connectReplicasCount = 2;

        final Map<String, Object> exceptedConfig = StUtils.loadProperties("group.id=" + KafkaConnectResources.componentName(testStorage.getClusterName()) + "\n" +
                "key.converter=org.apache.kafka.connect.json.JsonConverter\n" +
                "value.converter=org.apache.kafka.connect.json.JsonConverter\n" +
                "config.storage.replication.factor=-1\n" +
                "offset.storage.replication.factor=-1\n" +
                "status.storage.replication.factor=-1\n" +
                "config.storage.topic=" + KafkaConnectResources.metricsAndLogConfigMapName(testStorage.getClusterName()) + "\n" +
                "status.storage.topic=" + KafkaConnectResources.configStorageTopicStatus(testStorage.getClusterName()) + "\n" +
                "offset.storage.topic=" + KafkaConnectResources.configStorageTopicOffsets(testStorage.getClusterName()) + "\n");

        resourceManager.createResourceWithWait(
            NodePoolsConverter.convertNodePoolsIfNeeded(
                KafkaNodePoolTemplates.brokerPool(testStorage.getNamespaceName(), testStorage.getBrokerPoolName(), testStorage.getClusterName(), 3).build(),
                KafkaNodePoolTemplates.controllerPool(testStorage.getNamespaceName(), testStorage.getControllerPoolName(), testStorage.getClusterName(), 3).build()
            )
        );
        resourceManager.createResourceWithWait(KafkaTemplates.kafkaEphemeral(testStorage.getNamespaceName(), testStorage.getClusterName(), 3).build());
        resourceManager.createResourceWithWait(KafkaConnectTemplates.kafkaConnect(testStorage.getNamespaceName(), testStorage.getClusterName(), connectReplicasCount).build());

        // Test ManualRolling Update
        LOGGER.info("KafkaConnect manual rolling update");
        final Map<String, String> connectPodsSnapshot = PodUtils.podSnapshot(testStorage.getNamespaceName(), testStorage.getKafkaConnectSelector());
        StrimziPodSetUtils.annotateStrimziPodSet(testStorage.getNamespaceName(), KafkaConnectResources.componentName(testStorage.getClusterName()), Collections.singletonMap(Annotations.ANNO_STRIMZI_IO_MANUAL_ROLLING_UPDATE, "true"));
        RollingUpdateUtils.waitTillComponentHasRolled(testStorage.getNamespaceName(), testStorage.getKafkaConnectSelector(), connectReplicasCount, connectPodsSnapshot);

        final String podName = PodUtils.getPodNameByPrefix(testStorage.getNamespaceName(), KafkaConnectResources.componentName(testStorage.getClusterName()));
        final String kafkaPodJson = ReadWriteUtils.writeObjectToJsonString(kubeClient(testStorage.getNamespaceName()).getPod(podName));

        assertThat(kafkaPodJson, hasJsonPath(StUtils.globalVariableJsonPathBuilder(0, "KAFKA_CONNECT_BOOTSTRAP_SERVERS"),
                hasItem(KafkaResources.tlsBootstrapAddress(testStorage.getClusterName()))));
        assertThat(StUtils.getPropertiesFromJson(0, kafkaPodJson, "KAFKA_CONNECT_CONFIGURATION"), is(exceptedConfig));
        VerificationUtils.verifyClusterOperatorConnectDockerImage(clusterOperator.getDeploymentNamespace(), testStorage.getNamespaceName(), testStorage.getClusterName());

        VerificationUtils.verifyPodsLabels(testStorage.getNamespaceName(), KafkaConnectResources.componentName(testStorage.getClusterName()), testStorage.getKafkaConnectSelector());
        VerificationUtils.verifyServiceLabels(testStorage.getNamespaceName(), KafkaConnectResources.serviceName(testStorage.getClusterName()), testStorage.getKafkaConnectSelector());
        VerificationUtils.verifyConfigMapsLabels(testStorage.getNamespaceName(), testStorage.getClusterName(), "");
        VerificationUtils.verifyServiceAccountsLabels(testStorage.getNamespaceName(), testStorage.getClusterName());
    }

    @ParallelNamespaceTest
    @Tag(SANITY)
    @Tag(SMOKE)
    @TestDoc(
        description = @Desc("This test case verifies pausing, stopping and running of connector via 'spec.pause' or 'spec.state' specification."),
        steps = {
            @Step(value = "Deploy prerequisites for running FileSink KafkaConnector, that is KafkaTopic, Kafka cluster, KafkaConnect, and FileSink KafkaConnector.", expected = "All resources are deployed and ready."),
            @Step(value = "Pause and run connector by modifying 'spec.pause' property of Connector, while also producing messages when connector pauses.", expected = "Connector is paused and resumed as expected, after connector is resumed, produced messages are present in destination file, indicating connector resumed correctly."),
            @Step(value = "Stop and run connector by modifying 'spec.state' property of Connector (with priority over now set 'spec.pause=false'), while also producing messages when connector stops.", expected = "Connector stops and resumes as expected, after resuming, produced messages are present in destination file, indicating connector resumed correctly."),
            @Step(value = "Pause and run connector by modifying 'spec.state' property of Connector (with priority over now set 'spec.pause=false'), while also producing messages when connector pauses.", expected = "Connector pauses and resumes as expected, after resuming, produced messages are present in destination file, indicating connector resumed correctly.")
        },
        labels = {
            @Label(value = "connect")
        }
    )
    void testKafkaConnectAndConnectorStateWithFileSinkPlugin() {
        final TestStorage testStorage = new TestStorage(ResourceManager.getTestContext());

        resourceManager.createResourceWithWait(
            NodePoolsConverter.convertNodePoolsIfNeeded(
                KafkaNodePoolTemplates.brokerPool(testStorage.getNamespaceName(), testStorage.getBrokerPoolName(), testStorage.getClusterName(), 3).build(),
                KafkaNodePoolTemplates.controllerPool(testStorage.getNamespaceName(), testStorage.getControllerPoolName(), testStorage.getClusterName(), 3).build()
            )
        );
        resourceManager.createResourceWithWait(KafkaTemplates.kafkaEphemeral(testStorage.getNamespaceName(), testStorage.getClusterName(), 3).build());
        resourceManager.createResourceWithWait(KafkaTopicTemplates.topic(testStorage).build());
        resourceManager.createResourceWithWait(KafkaConnectTemplates.kafkaConnectWithFilePlugin(testStorage.getNamespaceName(), testStorage.getClusterName(), 1)
            .editMetadata()
                .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
            .endMetadata()
            .editSpec()
                .addToConfig("key.converter.schemas.enable", false)
                .addToConfig("value.converter.schemas.enable", false)
                .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
            .endSpec()
            .build());

        final String connectPodName = kubeClient(testStorage.getNamespaceName()).listPodsByPrefixInName(KafkaConnectResources.componentName(testStorage.getClusterName())).get(0).getMetadata().getName();

        KafkaConnectUtils.waitUntilKafkaConnectRestApiIsAvailable(testStorage.getNamespaceName(), connectPodName);

        LOGGER.info("Creating KafkaConnector: {}/{} without 'spec.pause' or 'spec.state' specified", testStorage.getNamespaceName(), testStorage.getClusterName());
        resourceManager.createResourceWithWait(KafkaConnectorTemplates.kafkaConnector(testStorage.getNamespaceName(), testStorage.getClusterName())
            .editSpec()
                .withClassName("org.apache.kafka.connect.file.FileStreamSinkConnector")
                .addToConfig("topics", testStorage.getTopicName())
                .addToConfig("file", TestConstants.DEFAULT_SINK_FILE_PATH)
                .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
            .endSpec()
            .build());

        // TODO in future releases can be removed completely as 'spec.pause' property is becoming deprecated.
        LOGGER.info("Verify pausing and running KafkaConnector, by setting 'spec.pause' property to 'true' and 'false'");
        verifySinkConnectorByBlockAndUnblock(testStorage, connectPodName,
            connector -> connector.getSpec().setPause(true),
            connector -> connector.getSpec().setPause(false));

        LOGGER.info("Verify stopping and running KafkaConnector, by setting 'spec.state' property to '' and false");
        verifySinkConnectorByBlockAndUnblock(testStorage, connectPodName,
            connector -> connector.getSpec().setState(ConnectorState.STOPPED),
            connector -> connector.getSpec().setState(ConnectorState.RUNNING));

        LOGGER.info("Verify pausing and running KafkaConnector, by setting 'spec.state' property to 'paused' and 'running'");
        verifySinkConnectorByBlockAndUnblock(testStorage, connectPodName,
            connector -> connector.getSpec().setState(ConnectorState.PAUSED),
            connector -> connector.getSpec().setState(ConnectorState.RUNNING));
    }

    @ParallelNamespaceTest
    @TestDoc(
        description = @Desc("Test verifying Kafka Connect functionalities with Plain and SCRAM-SHA authentication."),
        steps = {
            @Step(value = "Create object instance of TestStorage", expected = "Instance of TestStorage is created"),
            @Step(value = "Create NodePools using resourceManager based on the configuration", expected = "NodePools for broker and controller are created or not based on configuration"),
            @Step(value = "Deploy Kafka with SCRAM-SHA-512 listener", expected = "Kafka is deployed with the specified listener authentication"),
            @Step(value = "Create KafkaUser with SCRAM-SHA authentication", expected = "KafkaUser is created using SCRAM-SHA authentication with the given credentials"),
            @Step(value = "Create KafkaTopic", expected = "KafkaTopic is created"),
            @Step(value = "Deploy KafkaConnect with SCRAM-SHA-512 authentication", expected = "KafkaConnect instance is deployed and connected to Kafka"),
            @Step(value = "Deploy required resources for NetworkPolicy, KafkaConnect, and ScraperPod", expected = "Resources are successfully deployed with NetworkPolicy applied"),
            @Step(value = "Create FileStreamSink connector", expected = "FileStreamSink connector is created successfully"),
            @Step(value = "Create Kafka client with SCRAM-SHA-PLAIN authentication and send messages", expected = "Messages are produced and consumed successfully using Kafka client with SCRAM-SHA-PLAIN authentication"),
            @Step(value = "Verify messages in KafkaConnect file sink", expected = "FileSink contains the expected number of messages")
        },
        labels = {
            @Label(value = "connect")
        }
    )
    void testKafkaConnectWithPlainAndScramShaAuthentication() {
        final TestStorage testStorage = new TestStorage(ResourceManager.getTestContext());

        resourceManager.createResourceWithWait(
            NodePoolsConverter.convertNodePoolsIfNeeded(
                KafkaNodePoolTemplates.brokerPool(testStorage.getNamespaceName(), testStorage.getBrokerPoolName(), testStorage.getClusterName(), 3).build(),
                KafkaNodePoolTemplates.controllerPool(testStorage.getNamespaceName(), testStorage.getControllerPoolName(), testStorage.getClusterName(), 3).build()
            )
        );
        // Use a Kafka with plain listener disabled
        resourceManager.createResourceWithWait(KafkaTemplates.kafkaEphemeral(testStorage.getNamespaceName(), testStorage.getClusterName(), 3)
            .editSpec()
                .editKafka()
                    .withListeners(new GenericKafkaListenerBuilder()
                            .withName(TestConstants.PLAIN_LISTENER_DEFAULT_NAME)
                            .withPort(9092)
                            .withType(KafkaListenerType.INTERNAL)
                            .withTls(false)
                            .withAuth(new KafkaListenerAuthenticationScramSha512())
                            .build())
                .endKafka()
            .endSpec()
            .build());

        KafkaUser kafkaUser =  KafkaUserTemplates.scramShaUser(testStorage.getNamespaceName(), testStorage.getUsername(), testStorage.getClusterName()).build();

        resourceManager.createResourceWithWait(kafkaUser);
        resourceManager.createResourceWithWait(KafkaTopicTemplates.topic(testStorage).build());

        KafkaConnect connect = KafkaConnectTemplates.kafkaConnectWithFilePlugin(testStorage.getNamespaceName(), testStorage.getClusterName(), 1)
            .editSpec()
                .withBootstrapServers(KafkaResources.plainBootstrapAddress(testStorage.getClusterName()))
                .withNewKafkaClientAuthenticationScramSha512()
                    .withUsername(testStorage.getUsername())
                    .withPasswordSecret(new PasswordSecretSourceBuilder()
                        .withSecretName(testStorage.getUsername())
                        .withPassword("password")
                        .build())
                .endKafkaClientAuthenticationScramSha512()
                .addToConfig("key.converter.schemas.enable", false)
                .addToConfig("value.converter.schemas.enable", false)
                .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                .withVersion(Environment.ST_KAFKA_VERSION)
                .withReplicas(1)
            .endSpec()
            .build();
        // This is required to be able to remove the TLS setting, the builder cannot remove it
        connect.getSpec().setTls(null);
        
        resourceManager.createResourceWithWait(connect, ScraperTemplates.scraperPod(testStorage.getNamespaceName(), testStorage.getScraperName()).build());

        LOGGER.info("Deploying NetworkPolicies for KafkaConnect");
        NetworkPolicyResource.deployNetworkPolicyForResource(connect, KafkaConnectResources.componentName(testStorage.getClusterName()));

        final String kafkaConnectPodName = kubeClient(testStorage.getNamespaceName()).listPodsByPrefixInName(KafkaConnectResources.componentName(testStorage.getClusterName())).get(0).getMetadata().getName();
        final String scraperPodName = kubeClient(testStorage.getNamespaceName()).listPodsByPrefixInName(testStorage.getScraperName()).get(0).getMetadata().getName();

        KafkaConnectUtils.waitUntilKafkaConnectRestApiIsAvailable(testStorage.getNamespaceName(), kafkaConnectPodName);

        LOGGER.info("Creating FileStreamSink KafkaConnector via Pod: {}/{} with Topic: {}", testStorage.getNamespaceName(), scraperPodName, testStorage.getTopicName());
        KafkaConnectorUtils.createFileSinkConnector(testStorage.getNamespaceName(), scraperPodName, testStorage.getTopicName(),
            TestConstants.DEFAULT_SINK_FILE_PATH, KafkaConnectResources.url(testStorage.getClusterName(), testStorage.getNamespaceName(), 8083));

        final KafkaClients plainScramShaClients = ClientUtils.getInstantScramShaOverPlainClients(testStorage);
        resourceManager.createResourceWithWait(
            plainScramShaClients.producerScramShaPlainStrimzi(),
            plainScramShaClients.consumerScramShaPlainStrimzi());
        ClientUtils.waitForInstantClientSuccess(testStorage);
        KafkaConnectUtils.waitForMessagesInKafkaConnectFileSink(testStorage.getNamespaceName(), kafkaConnectPodName, TestConstants.DEFAULT_SINK_FILE_PATH, testStorage.getMessageCount());
    }

    @ParallelNamespaceTest
    @Tag(CONNECTOR_OPERATOR)
    @TestDoc(
        description = @Desc("Test the functionality of Kafka Connect with a File Sink Plugin in a parallel namespace setup."),
        steps = {
            @Step(value = "Create and configure test storage", expected = "Test storage is set up with necessary configurations."),
            @Step(value = "Create NodePools using resourceManager based on the configuration", expected = "NodePools for broker and controller are created or not based on configuration"),
            @Step(value = "Create and wait for the broker and controller pools", expected = "Broker and controller pools are created and running."),
            @Step(value = "Deploy and configure Kafka Connect with File Sink Plugin", expected = "Kafka Connect with File Sink Plugin is deployed and configured."),
            @Step(value = "Deploy Network Policies for Kafka Connect", expected = "Network Policies are successfully deployed for Kafka Connect."),
            @Step(value = "Create and wait for Kafka Connector", expected = "Kafka Connector is created and running."),
            @Step(value = "Deploy and configure scraper pod", expected = "Scraper pod is deployed and configured."),
            @Step(value = "Deploy and configure Kafka clients", expected = "Kafka clients are deployed and configured."),
            @Step(value = "Execute assertions to verify the Kafka Connector configuration and status", expected = "Assertions confirm the Kafka Connector is successfully deployed, has the correct configuration, and is running.")
        },
        labels = {
            @Label(value = "connect")
        }
    )
    void testKafkaConnectAndConnectorFileSinkPlugin() {
        final TestStorage testStorage = new TestStorage(ResourceManager.getTestContext());

        resourceManager.createResourceWithWait(
            NodePoolsConverter.convertNodePoolsIfNeeded(
                KafkaNodePoolTemplates.brokerPool(testStorage.getNamespaceName(), testStorage.getBrokerPoolName(), testStorage.getClusterName(), 3).build(),
                KafkaNodePoolTemplates.controllerPool(testStorage.getNamespaceName(), testStorage.getControllerPoolName(), testStorage.getClusterName(), 3).build()
            )
        );
        resourceManager.createResourceWithWait(KafkaTemplates.kafkaEphemeral(testStorage.getNamespaceName(), testStorage.getClusterName(), 3).build());

        KafkaConnect connect = KafkaConnectTemplates.kafkaConnectWithFilePlugin(testStorage.getNamespaceName(), testStorage.getClusterName(), 1)
            .editMetadata()
                .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
            .endMetadata()
            .editSpec()
                .addToConfig("key.converter.schemas.enable", false)
                .addToConfig("value.converter.schemas.enable", false)
            .endSpec()
            .build();

        resourceManager.createResourceWithWait(connect, ScraperTemplates.scraperPod(testStorage.getNamespaceName(), testStorage.getScraperName()).build());

        String connectorName = "license-source";

        LOGGER.info("Deploying NetworkPolicies for KafkaConnect");
        NetworkPolicyResource.deployNetworkPolicyForResource(connect, KafkaConnectResources.componentName(testStorage.getClusterName()));

        resourceManager.createResourceWithWait(KafkaConnectorTemplates.kafkaConnector(testStorage.getNamespaceName(), connectorName, testStorage.getClusterName(), 2)
            .editSpec()
                .addToConfig("topic", testStorage.getTopicName())
            .endSpec()
            .build());

        final String scraperPodName = kubeClient(testStorage.getNamespaceName()).listPodsByPrefixInName(testStorage.getScraperName()).get(0).getMetadata().getName();

        final KafkaClients kafkaClients = ClientUtils.getInstantPlainClients(testStorage);
        resourceManager.createResourceWithWait(kafkaClients.consumerStrimzi());
        ClientUtils.waitForInstantConsumerClientSuccess(testStorage);

        String service = KafkaConnectResources.url(testStorage.getClusterName(), testStorage.getNamespaceName(), 8083);
        String output = cmdKubeClient(testStorage.getNamespaceName()).execInPod(scraperPodName, "/bin/bash", "-c", "curl " + service + "/connectors/" + connectorName).out();
        assertThat(output, containsString("\"name\":\"license-source\""));
        assertThat(output, containsString("\"connector.class\":\"org.apache.kafka.connect.file.FileStreamSourceConnector\""));
        assertThat(output, containsString("\"tasks.max\":\"2\""));
        assertThat(output, containsString("\"topic\":\"" + testStorage.getTopicName() + "\""));
    }

    @ParallelNamespaceTest
    @TestDoc(
        description = @Desc("Test ensuring the JVM options and resource requests/limits are correctly applied to Kafka Connect components."),
        steps = {
            @Step(value = "Create TestStorage instance", expected = "TestStorage instance is created"),
            @Step(value = "Create NodePools using resourceManager based on the configuration", expected = "NodePools for broker and controller are created or not based on configuration"),
            @Step(value = "Create broker and controller node pools", expected = "Node pools are created and ready"),
            @Step(value = "Create Kafka cluster", expected = "Kafka cluster is created and operational"),
            @Step(value = "Setup JVM options and resource requirements for Kafka Connect", expected = "Kafka Connect is configured with specified JVM options and resources"),
            @Step(value = "Verify JVM options and resource requirements", expected = "JVM options and resource requests/limits are correctly applied to the Kafka Connect pod")
        },
        labels = {
            @Label(value = "connect")
        }
    )
    void testJvmAndResources() {
        final TestStorage testStorage = new TestStorage(ResourceManager.getTestContext());

        resourceManager.createResourceWithWait(
            NodePoolsConverter.convertNodePoolsIfNeeded(
                KafkaNodePoolTemplates.brokerPool(testStorage.getNamespaceName(), testStorage.getBrokerPoolName(), testStorage.getClusterName(), 3).build(),
                KafkaNodePoolTemplates.controllerPool(testStorage.getNamespaceName(), testStorage.getControllerPoolName(), testStorage.getClusterName(), 3).build()
            )
        );
        resourceManager.createResourceWithWait(KafkaTemplates.kafkaEphemeral(testStorage.getNamespaceName(), testStorage.getClusterName(), 3).build());

        Map<String, String> jvmOptionsXX = new HashMap<>();
        jvmOptionsXX.put("UseG1GC", "true");

        resourceManager.createResourceWithWait(KafkaConnectTemplates.kafkaConnect(testStorage.getNamespaceName(), testStorage.getClusterName(), 1)
            .editSpec()
                .withResources(new ResourceRequirementsBuilder()
                    .addToLimits("memory", new Quantity("400M"))
                    .addToLimits("cpu", new Quantity("2"))
                    .addToRequests("memory", new Quantity("300M"))
                    .addToRequests("cpu", new Quantity("1"))
                    .build())
                .withNewJvmOptions()
                    .withXmx("200m")
                    .withXms("200m")
                    .withXx(jvmOptionsXX)
                .endJvmOptions()
            .endSpec()
            .build());

        String podName = PodUtils.getPodNameByPrefix(testStorage.getNamespaceName(), KafkaConnectResources.componentName(testStorage.getClusterName()));
        VerificationUtils.assertPodResourceRequests(testStorage.getNamespaceName(), podName, KafkaConnectResources.componentName(testStorage.getClusterName()),
                "400M", "2", "300M", "1");
        VerificationUtils.assertJvmOptions(testStorage.getNamespaceName(), podName, KafkaConnectResources.componentName(testStorage.getClusterName()),
                "-Xmx200m", "-Xms200m", "-XX:+UseG1GC");
    }

    @ParallelNamespaceTest
    @Tag(COMPONENT_SCALING)
    @TestDoc(
        description = @Desc("Test verifying the scaling up and down functionality of Kafka Connect in a Kubernetes environment."),
        steps = {
            @Step(value = "Create TestStorage object instance", expected = "Instance of TestStorage is created"),
            @Step(value = "Create NodePools using resourceManager based on the configuration", expected = "NodePools for broker and controller are created or not based on configuration"),
            @Step(value = "Create resources for KafkaNodePools and KafkaCluster", expected = "Resources are created and ready"),
            @Step(value = "Deploy Kafka Connect with file plugin", expected = "Kafka Connect is deployed with 1 initial replica"),
            @Step(value = "Verify the initial replica count", expected = "Initial replica count is verified to be 1"),
            @Step(value = "Scale Kafka Connect up to a higher number of replicas", expected = "Kafka Connect is scaled up successfully"),
            @Step(value = "Verify the new replica count after scaling up", expected = "New replica count is verified to be the scaled up count"),
            @Step(value = "Scale Kafka Connect down to the initial number of replicas", expected = "Kafka Connect is scaled down successfully"),
            @Step(value = "Verify the replica count after scaling down", expected = "Replica count is verified to be the initial count")
        },
        labels = {
            @Label(value = "connect")
        }
    )
    void testKafkaConnectScaleUpScaleDown() {
        final TestStorage testStorage = new TestStorage(ResourceManager.getTestContext());

        resourceManager.createResourceWithWait(
            NodePoolsConverter.convertNodePoolsIfNeeded(
                KafkaNodePoolTemplates.brokerPool(testStorage.getNamespaceName(), testStorage.getBrokerPoolName(), testStorage.getClusterName(), 3).build(),
                KafkaNodePoolTemplates.controllerPool(testStorage.getNamespaceName(), testStorage.getControllerPoolName(), testStorage.getClusterName(), 3).build()
            )
        );
        resourceManager.createResourceWithWait(KafkaTemplates.kafkaEphemeral(testStorage.getNamespaceName(), testStorage.getClusterName(), 3).build());

        resourceManager.createResourceWithWait(KafkaConnectTemplates.kafkaConnectWithFilePlugin(testStorage.getNamespaceName(), testStorage.getClusterName(), 1).build());

        // kafka cluster Connect already deployed
        List<Pod> connectPods = kubeClient(testStorage.getNamespaceName()).listPods(Labels.STRIMZI_NAME_LABEL, KafkaConnectResources.componentName(testStorage.getClusterName()));
        int initialReplicas = connectPods.size();
        assertThat(initialReplicas, is(1));
        final int scaleTo = initialReplicas + 3;

        LOGGER.info("Scaling up to {}", scaleTo);
        KafkaConnectResource.replaceKafkaConnectResourceInSpecificNamespace(testStorage.getClusterName(), c -> c.getSpec().setReplicas(scaleTo), testStorage.getNamespaceName());

        PodUtils.waitForPodsReady(testStorage.getNamespaceName(), testStorage.getKafkaConnectSelector(), scaleTo, true);
        connectPods = kubeClient(testStorage.getNamespaceName()).listPods(Labels.STRIMZI_NAME_LABEL, KafkaConnectResources.componentName(testStorage.getClusterName()));
        assertThat(connectPods.size(), is(scaleTo));

        LOGGER.info("Scaling down to {}", initialReplicas);
        KafkaConnectResource.replaceKafkaConnectResourceInSpecificNamespace(testStorage.getClusterName(), c -> c.getSpec().setReplicas(initialReplicas), testStorage.getNamespaceName());

        PodUtils.waitForPodsReady(testStorage.getNamespaceName(), testStorage.getKafkaConnectSelector(), initialReplicas, true);
        connectPods = kubeClient(testStorage.getNamespaceName()).listPods(Labels.STRIMZI_NAME_LABEL, KafkaConnectResources.componentName(testStorage.getClusterName()));
        assertThat(connectPods.size(), is(initialReplicas));
    }

    @ParallelNamespaceTest
    @TestDoc(
        description = @Desc("This test verifies that Kafka Connect works with TLS and TLS client authentication."),
        steps = {
            @Step(value = "Create test storage instance", expected = "Test storage instance is created"),
            @Step(value = "Create NodePools using resourceManager based on the configuration", expected = "NodePools for broker and controller are created or not based on configuration"),
            @Step(value = "Create resources for Kafka broker and Kafka Connect components", expected = "Resources are created and ready"),
            @Step(value = "Configure Kafka broker with TLS listener and client authentication", expected = "Kafka broker is configured correctly"),
            @Step(value = "Deploy Kafka user with TLS authentication", expected = "Kafka user is deployed with TLS authentication"),
            @Step(value = "Deploy Kafka topic", expected = "Kafka topic is deployed"),
            @Step(value = "Configure and deploy Kafka Connect with TLS and TLS client authentication", expected = "Kafka Connect is configured and deployed correctly"),
            @Step(value = "Deploy Network Policies for Kafka Connect", expected = "Network Policies are deployed"),
            @Step(value = "Create FileStreamSink KafkaConnector via scraper pod", expected = "KafkaConnector is created correctly"),
            @Step(value = "Deploy TLS clients and produce/consume messages", expected = "Messages are produced and consumed successfully"),
            @Step(value = "Verify messages in Kafka Connect FileSink", expected = "Messages are verified in Kafka Connect FileSink")
        },
        labels = {
            @Label(value = "connect")
        }
    )
    void testSecretsWithKafkaConnectWithTlsAndTlsClientAuthentication() {
        final TestStorage testStorage = new TestStorage(ResourceManager.getTestContext());

        resourceManager.createResourceWithWait(
            NodePoolsConverter.convertNodePoolsIfNeeded(
                KafkaNodePoolTemplates.brokerPool(testStorage.getNamespaceName(), testStorage.getBrokerPoolName(), testStorage.getClusterName(), 3).build(),
                KafkaNodePoolTemplates.controllerPool(testStorage.getNamespaceName(), testStorage.getControllerPoolName(), testStorage.getClusterName(), 3).build()
            )
        );
        resourceManager.createResourceWithWait(KafkaTemplates.kafkaEphemeral(testStorage.getNamespaceName(), testStorage.getClusterName(), 3)
            .editSpec()
                .editKafka()
                    .withListeners(new GenericKafkaListenerBuilder()
                            .withName(TestConstants.TLS_LISTENER_DEFAULT_NAME)
                            .withPort(9093)
                            .withType(KafkaListenerType.INTERNAL)
                            .withTls(true)
                            .withAuth(new KafkaListenerAuthenticationTls())
                            .build())
                .endKafka()
            .endSpec()
            .build());

        KafkaUser kafkaUser = KafkaUserTemplates.tlsUser(testStorage).build();

        resourceManager.createResourceWithWait(kafkaUser);
        resourceManager.createResourceWithWait(KafkaTopicTemplates.topic(testStorage).build());

        KafkaConnect connect = KafkaConnectTemplates.kafkaConnectWithFilePlugin(testStorage.getNamespaceName(), testStorage.getClusterName(), 1)
            .editSpec()
                .addToConfig("key.converter.schemas.enable", false)
                .addToConfig("value.converter.schemas.enable", false)
                .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                .withNewTls()
                    .addNewTrustedCertificate()
                        .withSecretName(testStorage.getClusterName() + "-cluster-ca-cert")
                        .withCertificate("ca.crt")
                    .endTrustedCertificate()
                .endTls()
                .withBootstrapServers(testStorage.getClusterName() + "-kafka-bootstrap:9093")
                .withNewKafkaClientAuthenticationTls()
                    .withNewCertificateAndKey()
                        .withSecretName(testStorage.getUsername())
                        .withCertificate("user.crt")
                        .withKey("user.key")
                    .endCertificateAndKey()
                .endKafkaClientAuthenticationTls()
            .endSpec()
            .build();

        resourceManager.createResourceWithWait(connect, ScraperTemplates.scraperPod(testStorage.getNamespaceName(), testStorage.getScraperName()).build());

        LOGGER.info("Deploying NetworkPolicies for KafkaConnect");
        NetworkPolicyResource.deployNetworkPolicyForResource(connect, KafkaConnectResources.componentName(testStorage.getClusterName()));

        final String kafkaConnectPodName = kubeClient(testStorage.getNamespaceName()).listPodsByPrefixInName(KafkaConnectResources.componentName(testStorage.getClusterName())).get(0).getMetadata().getName();
        final String scraperPodName = kubeClient(testStorage.getNamespaceName()).listPodsByPrefixInName(testStorage.getScraperName()).get(0).getMetadata().getName();

        KafkaConnectUtils.waitUntilKafkaConnectRestApiIsAvailable(testStorage.getNamespaceName(), kafkaConnectPodName);

        LOGGER.info("Creating FileStreamSink KafkaConnector via Pod: {}/{} with Topic: {}", testStorage.getNamespaceName(), scraperPodName, testStorage.getTopicName());
        KafkaConnectorUtils.createFileSinkConnector(testStorage.getNamespaceName(), scraperPodName, testStorage.getTopicName(),
            TestConstants.DEFAULT_SINK_FILE_PATH, KafkaConnectResources.url(testStorage.getClusterName(), testStorage.getNamespaceName(), 8083));

        final KafkaClients kafkaClients = ClientUtils.getInstantTlsClients(testStorage);
        resourceManager.createResourceWithWait(kafkaClients.producerTlsStrimzi(testStorage.getClusterName()), kafkaClients.consumerTlsStrimzi(testStorage.getClusterName()));
        ClientUtils.waitForInstantClientSuccess(testStorage);

        KafkaConnectUtils.waitForMessagesInKafkaConnectFileSink(testStorage.getNamespaceName(), kafkaConnectPodName, TestConstants.DEFAULT_SINK_FILE_PATH, testStorage.getMessageCount());
    }

    @ParallelNamespaceTest
    @TestDoc(
        description = @Desc("Test validating Kafka Connect with TLS and SCRAM-SHA authentication along with associated resources setup and verification."),
        steps = {
            @Step(value = "Initialize test storage", expected = "Instances created successfully"),
            @Step(value = "Create Kafka node pools (broker and controller)", expected = "Node pools created and ready"),
            @Step(value = "Deploy Kafka cluster with TLS and SCRAM-SHA-512 authentication", expected = "Kafka cluster deployed with listeners configured"),
            @Step(value = "Create Kafka user with SCRAM-SHA-512", expected = "User created successfully"),
            @Step(value = "Deploy Kafka topic", expected = "Topic created successfully"),
            @Step(value = "Deploy Kafka Connect with TLS and SCRAM-SHA-512 authentication", expected = "Kafka Connect deployed with plugins and configuration"),
            @Step(value = "Deploy scraper pod for testing Kafka Connect", expected = "Scraper pod deployed successfully"),
            @Step(value = "Deploy NetworkPolicies for Kafka Connect", expected = "NetworkPolicies applied successfully"),
            @Step(value = "Create and configure FileStreamSink KafkaConnector", expected = "FileStreamSink KafkaConnector created and configured"),
            @Step(value = "Create Kafka clients for SCRAM-SHA-512 over TLS", expected = "Kafka clients (producer and consumer) created successfully"),
            @Step(value = "Wait for client operations to succeed", expected = "Message production and consumption verified"),
            @Step(value = "Verify messages in Kafka Connect file sink", expected = "Messages found in the specified file path")
        },
        labels = {
            @Label(value = "connect")
        }
    )
    void testSecretsWithKafkaConnectWithTlsAndScramShaAuthentication() {
        final TestStorage testStorage = new TestStorage(ResourceManager.getTestContext());

        resourceManager.createResourceWithWait(
            NodePoolsConverter.convertNodePoolsIfNeeded(
                KafkaNodePoolTemplates.brokerPool(testStorage.getNamespaceName(), testStorage.getBrokerPoolName(), testStorage.getClusterName(), 3).build(),
                KafkaNodePoolTemplates.controllerPool(testStorage.getNamespaceName(), testStorage.getControllerPoolName(), testStorage.getClusterName(), 3).build()
            )
        );
        resourceManager.createResourceWithWait(KafkaTemplates.kafkaEphemeral(testStorage.getNamespaceName(), testStorage.getClusterName(), 3)
            .editSpec()
                .editKafka()
                    .withListeners(new GenericKafkaListenerBuilder()
                            .withName(TestConstants.TLS_LISTENER_DEFAULT_NAME)
                            .withPort(9093)
                            .withType(KafkaListenerType.INTERNAL)
                            .withTls(true)
                            .withAuth(new KafkaListenerAuthenticationScramSha512())
                            .build())
                .endKafka()
            .endSpec()
            .build());

        KafkaUser kafkaUser = KafkaUserTemplates.scramShaUser(testStorage).build();

        resourceManager.createResourceWithWait(kafkaUser);
        resourceManager.createResourceWithWait(KafkaTopicTemplates.topic(testStorage).build());

        KafkaConnect connect = KafkaConnectTemplates.kafkaConnectWithFilePlugin(testStorage.getNamespaceName(), testStorage.getClusterName(), 1)
            .editSpec()
                .addToConfig("key.converter.schemas.enable", false)
                .addToConfig("value.converter.schemas.enable", false)
                .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                .withNewTls()
                    .addNewTrustedCertificate()
                        .withSecretName(testStorage.getClusterName() + "-cluster-ca-cert")
                        .withCertificate("ca.crt")
                    .endTrustedCertificate()
                .endTls()
                .withBootstrapServers(testStorage.getClusterName() + "-kafka-bootstrap:9093")
                .withNewKafkaClientAuthenticationScramSha512()
                    .withUsername(testStorage.getUsername())
                    .withNewPasswordSecret()
                        .withSecretName(testStorage.getUsername())
                        .withPassword("password")
                    .endPasswordSecret()
                .endKafkaClientAuthenticationScramSha512()
            .endSpec()
            .build();

        resourceManager.createResourceWithWait(connect, ScraperTemplates.scraperPod(testStorage.getNamespaceName(), testStorage.getScraperName()).build());

        LOGGER.info("Deploying NetworkPolicies for KafkaConnect");
        NetworkPolicyResource.deployNetworkPolicyForResource(connect, KafkaConnectResources.componentName(testStorage.getClusterName()));

        final String kafkaConnectPodName = kubeClient(testStorage.getNamespaceName()).listPodsByPrefixInName(KafkaConnectResources.componentName(testStorage.getClusterName())).get(0).getMetadata().getName();
        final String scraperPodName = kubeClient(testStorage.getNamespaceName()).listPodsByPrefixInName(testStorage.getScraperName()).get(0).getMetadata().getName();

        LOGGER.info("Creating FileStreamSink KafkaConnector via Pod: {}/{} with Topic: {}", testStorage.getNamespaceName(), scraperPodName, testStorage.getTopicName());
        KafkaConnectorUtils.createFileSinkConnector(testStorage.getNamespaceName(), scraperPodName, testStorage.getTopicName(),
            TestConstants.DEFAULT_SINK_FILE_PATH, KafkaConnectResources.url(testStorage.getClusterName(), testStorage.getNamespaceName(), 8083));

        final KafkaClients kafkaClients = ClientUtils.getInstantScramShaOverTlsClients(testStorage);
        resourceManager.createResourceWithWait(kafkaClients.producerScramShaTlsStrimzi(testStorage.getClusterName()), kafkaClients.consumerScramShaTlsStrimzi(testStorage.getClusterName()));
        ClientUtils.waitForInstantClientSuccess(testStorage);

        KafkaConnectUtils.waitForMessagesInKafkaConnectFileSink(testStorage.getNamespaceName(), kafkaConnectPodName, TestConstants.DEFAULT_SINK_FILE_PATH, testStorage.getMessageCount());
    }

    @ParallelNamespaceTest
    @MicroShiftNotSupported("The test is using Connect Build feature that is not available on MicroShift")
    @TestDoc(
        description = @Desc("Test the automatic restart functionality of Kafka Connect tasks when they fail."),
        steps = {
            @Step(value = "Create test storage instance", expected = "Test storage instance is created"),
            @Step(value = "Create node pool resources", expected = "Node pool resources are created and waited for readiness"),
            @Step(value = "Create Kafka cluster", expected = "Kafka cluster is created and waited for readiness"),
            @Step(value = "Deploy EchoSink Kafka Connector with autor restart enabled", expected = "Kafka Connector is created with auto-restart enabled"),
            @Step(value = "Send first batch of messages", expected = "First batch of messages is sent to the topic"),
            @Step(value = "Ensure connection success for the first batch", expected = "Successfully produce the first batch of messages"),
            @Step(value = "Send second batch of messages", expected = "Second batch of messages is sent to the topic"),
            @Step(value = "Ensure connection success for the second batch", expected = "Successfully produce the second batch of messages"),
            @Step(value = "Verify task failure and auto-restart", expected = "Connector task fails and is automatically restarted"),
            @Step(value = "Wait for task to reach running state", expected = "Connector task returns to running state after recovery"),
            @Step(value = "Verify auto-restart count reset", expected = "Auto-restart count is reset to zero after task stability")
        },
        labels = {
            @Label(value = "connect")
        }
    )
    void testConnectorTaskAutoRestart() {
        final TestStorage testStorage = new TestStorage(ResourceManager.getTestContext());

        resourceManager.createResourceWithWait(
            NodePoolsConverter.convertNodePoolsIfNeeded(
                KafkaNodePoolTemplates.brokerPool(testStorage.getNamespaceName(), testStorage.getBrokerPoolName(), testStorage.getClusterName(), 3).build(),
                KafkaNodePoolTemplates.controllerPool(testStorage.getNamespaceName(), testStorage.getControllerPoolName(), testStorage.getClusterName(), 3).build()
            )
        );
        resourceManager.createResourceWithWait(KafkaTemplates.kafkaEphemeral(testStorage.getNamespaceName(), testStorage.getClusterName(), 3).build());

        final String imageFullPath = Environment.getImageOutputRegistry(testStorage.getNamespaceName(), TestConstants.ST_CONNECT_BUILD_IMAGE_NAME, String.valueOf(new Random().nextInt(Integer.MAX_VALUE)));

        resourceManager.createResourceWithWait(KafkaTopicTemplates.topic(testStorage.getNamespaceName(), testStorage.getTopicName(), testStorage.getClusterName(), 3, 3).build());

        final Plugin echoSinkPlugin = new PluginBuilder()
            .withName(TestConstants.ECHO_SINK_CONNECTOR_NAME)
            .withArtifacts(
                new JarArtifactBuilder()
                    .withUrl(TestConstants.ECHO_SINK_JAR_URL)
                    .withSha512sum(TestConstants.ECHO_SINK_JAR_CHECKSUM)
                    .build())
            .build();

        KafkaConnect connect = KafkaConnectTemplates.kafkaConnect(testStorage.getNamespaceName(), testStorage.getClusterName(), 1)
            .editMetadata()
                .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
            .endMetadata()
            .editOrNewSpec()
                .addToConfig("key.converter.schemas.enable", false)
                .addToConfig("value.converter.schemas.enable", false)
                .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                .withNewBuild()
                    .withPlugins(echoSinkPlugin)
                    .withOutput(KafkaConnectTemplates.dockerOutput(imageFullPath))
                .endBuild()
            .endSpec()
            .build();

        resourceManager.createResourceWithWait(connect, ScraperTemplates.scraperPod(testStorage.getNamespaceName(), testStorage.getScraperName()).build());
        LOGGER.info("Deploying NetworkPolicies for KafkaConnect");
        NetworkPolicyResource.deployNetworkPolicyForResource(connect, KafkaConnectResources.componentName(testStorage.getClusterName()));

        // How many messages should be sent and at what count should the test connector fail
        final int failMessageCount = 5;
        // For properly committing the records offset, we need to firstly send 4 messages and then 1
        // The reason is that in case we send all messages at once, the whole batch is processed and the exception is thrown in `put()` method of the EchoSinkTask
        // But if the exception is thrown inside the `put()` method, it can prevent from committing the offsets, which could end up in infinite restarts of the EchoSink Connector's task
        final int firstBatchMessageCount = 4;
        final int secondBatchMessageCount = 1;

        Map<String, Object> echoSinkConfig = new HashMap<>();
        echoSinkConfig.put("topics", testStorage.getTopicName());
        echoSinkConfig.put("level", "INFO");
        echoSinkConfig.put("fail.task.after.records", failMessageCount);

        LOGGER.info("Creating EchoSink KafkaConnector");
        resourceManager.createResourceWithWait(KafkaConnectorTemplates.kafkaConnector(testStorage.getNamespaceName(), TestConstants.ECHO_SINK_CONNECTOR_NAME, testStorage.getClusterName())
            .editOrNewSpec()
                .withTasksMax(1)
                .withClassName(TestConstants.ECHO_SINK_CLASS_NAME)
                .withConfig(echoSinkConfig)
                .withAutoRestart(new AutoRestartBuilder().withEnabled().build())
            .endSpec()
            .build());

        // Send first batch of messages to the topic
        KafkaClients kafkaClients = ClientUtils.getInstantPlainClientBuilder(testStorage)
            .withMessageCount(firstBatchMessageCount)
            .build();
        resourceManager.createResourceWithWait(kafkaClients.producerStrimzi());
        ClientUtils.waitForInstantProducerClientSuccess(testStorage);

        // Send second batch of messages to the topic
        kafkaClients = new KafkaClientsBuilder(kafkaClients)
            .withMessageCount(secondBatchMessageCount)
            .build();

        resourceManager.createResourceWithWait(kafkaClients.producerStrimzi());
        ClientUtils.waitForInstantProducerClientSuccess(testStorage);

        // After connector picks up messages from topic it fails task
        // If it's the first time echo-sink task failed - it's immediately restarted and connector adds count to autoRestartCount.
        KafkaConnectorUtils.waitForConnectorAutoRestartCount(testStorage.getNamespaceName(), TestConstants.ECHO_SINK_CONNECTOR_NAME, 1);
        // Give some time to connector task to get back to running state after recovery
        KafkaConnectorUtils.waitForConnectorTaskState(testStorage.getNamespaceName(), TestConstants.ECHO_SINK_CONNECTOR_NAME, 0, "RUNNING");
        // If task is in running state for about 2 minutes, it resets the auto-restart count back to 0
        KafkaConnectorUtils.waitForConnectorAutoRestartCount(testStorage.getNamespaceName(), TestConstants.ECHO_SINK_CONNECTOR_NAME, 0);
    }

    @ParallelNamespaceTest
    @TestDoc(
        description = @Desc("Test that verifies custom and updated environment variables and readiness/liveness probes for Kafka Connect."),
        steps = {
            @Step(value = "Create and configure Kafka Connect with initial values", expected = "Kafka Connect is created and configured with initial environment variables and readiness/liveness probes"),
            @Step(value = "Create NodePools using resourceManager based on the configuration", expected = "NodePools for broker and controller are created or not based on configuration"),
            @Step(value = "Verify initial configuration and environment variables", expected = "Initial configuration and environment variables are as expected"),
            @Step(value = "Update Kafka Connect configuration and environment variables", expected = "Kafka Connect configuration and environment variables are updated"),
            @Step(value = "Verify updated configuration and environment variables", expected = "Updated configuration and environment variables are as expected")
        },
        labels = {
            @Label(value = "connect")
        }
    )
    void testCustomAndUpdatedValues() {
        final TestStorage testStorage = new TestStorage(ResourceManager.getTestContext());
        final String usedVariable = "KAFKA_CONNECT_CONFIGURATION";

        LinkedHashMap<String, String> envVarGeneral = new LinkedHashMap<>();
        envVarGeneral.put("TEST_ENV_1", "test.env.one");
        envVarGeneral.put("TEST_ENV_2", "test.env.two");
        envVarGeneral.put(usedVariable, "test.value");

        LinkedHashMap<String, String> envVarUpdated = new LinkedHashMap<>();
        envVarUpdated.put("TEST_ENV_2", "updated.test.env.two");
        envVarUpdated.put("TEST_ENV_3", "test.env.three");

        Map<String, Object> connectConfig = new HashMap<>();
        connectConfig.put("config.storage.replication.factor", "-1");
        connectConfig.put("offset.storage.replication.factor", "-1");
        connectConfig.put("status.storage.replication.factor", "-1");

        final int initialDelaySeconds = 30;
        final int timeoutSeconds = 10;
        final int updatedInitialDelaySeconds = 31;
        final int updatedTimeoutSeconds = 11;
        final int periodSeconds = 10;
        final int successThreshold = 1;
        final int failureThreshold = 3;
        final int updatedPeriodSeconds = 5;
        final int updatedFailureThreshold = 1;

        resourceManager.createResourceWithWait(
            NodePoolsConverter.convertNodePoolsIfNeeded(
                KafkaNodePoolTemplates.brokerPool(testStorage.getNamespaceName(), testStorage.getBrokerPoolName(), testStorage.getClusterName(), 3).build(),
                KafkaNodePoolTemplates.controllerPool(testStorage.getNamespaceName(), testStorage.getControllerPoolName(), testStorage.getClusterName(), 1).build()
            )
        );
        resourceManager.createResourceWithWait(KafkaTemplates.kafkaEphemeral(testStorage.getNamespaceName(), testStorage.getClusterName(), 3, 1).build());
        resourceManager.createResourceWithWait(KafkaConnectTemplates.kafkaConnectWithFilePlugin(testStorage.getNamespaceName(), testStorage.getClusterName(), 1)
            .editSpec()
                .withNewTemplate()
                    .withNewConnectContainer()
                        .withEnv(StUtils.createContainerEnvVarsFromMap(envVarGeneral))
                    .endConnectContainer()
                .endTemplate()
                .withNewReadinessProbe()
                    .withInitialDelaySeconds(initialDelaySeconds)
                    .withTimeoutSeconds(timeoutSeconds)
                    .withPeriodSeconds(periodSeconds)
                    .withSuccessThreshold(successThreshold)
                    .withFailureThreshold(failureThreshold)
                .endReadinessProbe()
                .withNewLivenessProbe()
                    .withInitialDelaySeconds(initialDelaySeconds)
                    .withTimeoutSeconds(timeoutSeconds)
                    .withPeriodSeconds(periodSeconds)
                    .withSuccessThreshold(successThreshold)
                    .withFailureThreshold(failureThreshold)
                .endLivenessProbe()
            .endSpec()
            .build());

        Map<String, String> connectSnapshot = PodUtils.podSnapshot(testStorage.getNamespaceName(), testStorage.getKafkaConnectSelector());

        // Remove variable which is already in use
        envVarGeneral.remove(usedVariable);
        LOGGER.info("Verifying values before update");
        VerificationUtils.verifyReadinessAndLivenessProbes(testStorage.getNamespaceName(), KafkaConnectResources.componentName(testStorage.getClusterName()), KafkaConnectResources.componentName(testStorage.getClusterName()), initialDelaySeconds, timeoutSeconds,
                periodSeconds, successThreshold, failureThreshold);
        VerificationUtils.verifyContainerEnvVariables(testStorage.getNamespaceName(), KafkaConnectResources.componentName(testStorage.getClusterName()), KafkaConnectResources.componentName(testStorage.getClusterName()), envVarGeneral);

        LOGGER.info("Check if actual env variable {} has different value than {}", usedVariable, "test.value");
        assertThat(
                StUtils.checkEnvVarInPod(testStorage.getNamespaceName(), kubeClient().listPodsByPrefixInName(testStorage.getNamespaceName(), KafkaConnectResources.componentName(testStorage.getClusterName())).get(0).getMetadata().getName(), usedVariable),
                is(not("test.value"))
        );

        LOGGER.info("Updating values in MirrorMaker container");

        KafkaConnectResource.replaceKafkaConnectResourceInSpecificNamespace(testStorage.getClusterName(), kc -> {
            kc.getSpec().getTemplate().getConnectContainer().setEnv(StUtils.createContainerEnvVarsFromMap(envVarUpdated));
            kc.getSpec().setConfig(connectConfig);
            kc.getSpec().getLivenessProbe().setInitialDelaySeconds(updatedInitialDelaySeconds);
            kc.getSpec().getReadinessProbe().setInitialDelaySeconds(updatedInitialDelaySeconds);
            kc.getSpec().getLivenessProbe().setTimeoutSeconds(updatedTimeoutSeconds);
            kc.getSpec().getReadinessProbe().setTimeoutSeconds(updatedTimeoutSeconds);
            kc.getSpec().getLivenessProbe().setPeriodSeconds(updatedPeriodSeconds);
            kc.getSpec().getReadinessProbe().setPeriodSeconds(updatedPeriodSeconds);
            kc.getSpec().getLivenessProbe().setFailureThreshold(updatedFailureThreshold);
            kc.getSpec().getReadinessProbe().setFailureThreshold(updatedFailureThreshold);
        }, testStorage.getNamespaceName());

        RollingUpdateUtils.waitTillComponentHasRolledAndPodsReady(testStorage.getNamespaceName(), testStorage.getKafkaConnectSelector(), 1, connectSnapshot);

        LOGGER.info("Verifying values after update");
        VerificationUtils.verifyReadinessAndLivenessProbes(testStorage.getNamespaceName(), KafkaConnectResources.componentName(testStorage.getClusterName()), KafkaConnectResources.componentName(testStorage.getClusterName()), updatedInitialDelaySeconds, updatedTimeoutSeconds,
                updatedPeriodSeconds, successThreshold, updatedFailureThreshold);
        VerificationUtils.verifyContainerEnvVariables(testStorage.getNamespaceName(), KafkaConnectResources.componentName(testStorage.getClusterName()), KafkaConnectResources.componentName(testStorage.getClusterName()), envVarUpdated);
        VerificationUtils.verifyComponentConfiguration(testStorage.getNamespaceName(), KafkaConnectResources.componentName(testStorage.getClusterName()), KafkaConnectResources.componentName(testStorage.getClusterName()), "KAFKA_CONNECT_CONFIGURATION", connectConfig);
    }

    @ParallelNamespaceTest
    @Tag(CONNECTOR_OPERATOR)
    @Tag(ACCEPTANCE)
    @TestDoc(
        description = @Desc("Test validating multi-node Kafka Connect cluster creation, connector deployment, and message processing."),
        steps = {
            @Step(value = "Initialize test storage and determine connect cluster name", expected = "Test storage and cluster name properly initialized"),
            @Step(value = "Create broker and controller node pools", expected = "Broker and controller node pools created successfully"),
            @Step(value = "Deploy Kafka cluster in ephemeral mode", expected = "Kafka cluster deployed successfully"),
            @Step(value = "Create Kafka Connect cluster with default image", expected = "Kafka Connect cluster created with appropriate configuration"),
            @Step(value = "Create and configure Kafka Connector", expected = "Kafka Connector deployed and configured with correct settings"),
            @Step(value = "Verify the status of the Kafka Connector", expected = "Kafka Connector status retrieved and worker node identified"),
            @Step(value = "Deploy Kafka clients for producer and consumer", expected = "Kafka producer and consumer clients deployed"),
            @Step(value = "Verify that Kafka Connect writes messages to the specified file sink", expected = "Messages successfully written to the file sink by Kafka Connect")
        },
        labels = {
            @Label(value = "connect")
        }
    )
    void testMultiNodeKafkaConnectWithConnectorCreation() {
        final TestStorage testStorage = new TestStorage(ResourceManager.getTestContext());

        final String connectClusterName = testStorage.getClusterName() + "-2";

        resourceManager.createResourceWithWait(
            NodePoolsConverter.convertNodePoolsIfNeeded(
                KafkaNodePoolTemplates.brokerPool(testStorage.getNamespaceName(), testStorage.getBrokerPoolName(), testStorage.getClusterName(), 3).build(),
                KafkaNodePoolTemplates.controllerPool(testStorage.getNamespaceName(), testStorage.getControllerPoolName(), testStorage.getClusterName(), 3).build()
            )
        );
        resourceManager.createResourceWithWait(KafkaTemplates.kafkaEphemeral(testStorage.getNamespaceName(), testStorage.getClusterName(), 3).build());
        // Crate connect cluster with default connect image
        resourceManager.createResourceWithWait(KafkaConnectTemplates.kafkaConnectWithFilePlugin(testStorage.getNamespaceName(), testStorage.getClusterName(), 3)
            .editMetadata()
                .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
            .endMetadata()
            .editSpec()
                .addToConfig("group.id", connectClusterName)
                .addToConfig("offset.storage.topic", connectClusterName + "-offsets")
                .addToConfig("config.storage.topic", connectClusterName + "-config")
                .addToConfig("status.storage.topic", connectClusterName + "-status")
            .endSpec()
            .build());

        resourceManager.createResourceWithWait(KafkaConnectorTemplates.kafkaConnector(testStorage.getNamespaceName(), testStorage.getClusterName())
            .editSpec()
                .withClassName("org.apache.kafka.connect.file.FileStreamSinkConnector")
                .addToConfig("topics", testStorage.getTopicName())
                .addToConfig("file", TestConstants.DEFAULT_SINK_FILE_PATH)
                .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
            .endSpec()
            .build());

        String execConnectPod =  kubeClient(testStorage.getNamespaceName()).listPodsByPrefixInName(
            KafkaConnectResources.componentName(testStorage.getClusterName())).get(0).getMetadata().getName();
        JsonObject connectStatus = new JsonObject(cmdKubeClient(testStorage.getNamespaceName()).execInPod(
                execConnectPod,
                "curl", "-X", "GET", "http://localhost:8083/connectors/" + testStorage.getClusterName() + "/status").out()
        );

        String workerNode = connectStatus.getJsonObject("connector").getString("worker_id").split(":")[0];
        String connectorPodName = workerNode.substring(0, workerNode.indexOf("."));

        final KafkaClients kafkaClients = ClientUtils.getInstantPlainClients(testStorage);
        resourceManager.createResourceWithWait(kafkaClients.producerStrimzi(), kafkaClients.consumerStrimzi());
        ClientUtils.waitForInstantClientSuccess(testStorage);

        KafkaConnectUtils.waitForMessagesInKafkaConnectFileSink(testStorage.getNamespaceName(), connectorPodName, TestConstants.DEFAULT_SINK_FILE_PATH, testStorage.getMessageCount());
    }

    @Tag(CONNECTOR_OPERATOR)
    @ParallelNamespaceTest
    @TestDoc(
        description = @Desc("Test verifying Kafka connect TLS authentication with a username containing unusual characters."),
        steps = {
            @Step(value = "Set up a name of username containing dots and 64 characters", expected = ""),
            @Step(value = "Create NodePools using resourceManager based on the configuration", expected = "NodePools for broker and controller are created or not based on configuration"),
            @Step(value = "Create Kafka broker, controller, topic, and Kafka user with the specified username", expected = "Resources are created with the expected configurations"),
            @Step(value = "Setup Kafka Connect with the created Kafka instance and TLS authentication", expected = "Kafka Connect is set up with the expected configurations"),
            @Step(value = "Check if the user can produce messages to Kafka", expected = "Messages are produced successfully"),
            @Step(value = "Verify that Kafka Connect can consume messages", expected = "Messages are consumed successfully by Kafka Connect")
        },
        labels = {
            @Label(value = "connect")
        }
    )
    void testConnectTlsAuthWithWeirdUserName() {
        final TestStorage testStorage = new TestStorage(ResourceManager.getTestContext());

        // Create weird named user with . and maximum of 64 chars -> TLS
        final String weirdUserName = "jjglmahyijoambryleyxjjglmahy.ijoambryleyxjjglmahyijoambryleyxasd";

        resourceManager.createResourceWithWait(
            NodePoolsConverter.convertNodePoolsIfNeeded(
                KafkaNodePoolTemplates.brokerPool(testStorage.getNamespaceName(), testStorage.getBrokerPoolName(), testStorage.getClusterName(), 3).build(),
                KafkaNodePoolTemplates.controllerPool(testStorage.getNamespaceName(), testStorage.getControllerPoolName(), testStorage.getClusterName(), 3).build()
            )
        );
        resourceManager.createResourceWithWait(KafkaTemplates.kafkaEphemeral(testStorage.getNamespaceName(), testStorage.getClusterName(), 3)
            .editSpec()
                .editKafka()
                    .withListeners(new GenericKafkaListenerBuilder()
                            .withName(TestConstants.TLS_LISTENER_DEFAULT_NAME)
                            .withPort(9093)
                            .withType(KafkaListenerType.INTERNAL)
                            .withTls(true)
                            .withAuth(new KafkaListenerAuthenticationTls())
                            .build())
                .endKafka()
            .endSpec()
            .build());

        resourceManager.createResourceWithWait(KafkaTopicTemplates.topic(testStorage.getNamespaceName(), testStorage.getTopicName(), testStorage.getClusterName()).build());
        resourceManager.createResourceWithWait(KafkaUserTemplates.tlsUser(testStorage.getNamespaceName(), weirdUserName, testStorage.getClusterName()).build());
        resourceManager.createResourceWithWait(KafkaConnectTemplates.kafkaConnectWithFilePlugin(testStorage.getNamespaceName(), testStorage.getClusterName(), 1)
            .editMetadata()
                .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
            .endMetadata()
            .editSpec()
                .addToConfig("key.converter.schemas.enable", false)
                .addToConfig("value.converter.schemas.enable", false)
                .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                .withNewTls()
                    .withTrustedCertificates(new CertSecretSourceBuilder()
                        .withPattern("*.crt")
                        .withSecretName(KafkaResources.clusterCaCertificateSecretName(testStorage.getClusterName()))
                        .build())
                .endTls()
                .withNewKafkaClientAuthenticationTls()
                    .withNewCertificateAndKey()
                        .withSecretName(weirdUserName)
                        .withCertificate("user.crt")
                        .withKey("user.key")
                    .endCertificateAndKey()
                .endKafkaClientAuthenticationTls()
                .withBootstrapServers(KafkaResources.tlsBootstrapAddress(testStorage.getClusterName()))
            .endSpec()
            .build());
        resourceManager.createResourceWithWait(KafkaConnectorTemplates.kafkaConnector(testStorage.getNamespaceName(), testStorage.getClusterName())
            .editSpec()
                .withClassName("org.apache.kafka.connect.file.FileStreamSinkConnector")
                .addToConfig("topics", testStorage.getTopicName())
                .addToConfig("file", TestConstants.DEFAULT_SINK_FILE_PATH)
            .endSpec()
            .build());

        final KafkaClients clients = ClientUtils.getInstantTlsClientBuilder(testStorage)
            .withUsername(weirdUserName)
            .build();

        LOGGER.info("Checking if user is able to produce messages");
        resourceManager.createResourceWithWait(clients.producerTlsStrimzi(testStorage.getClusterName()));
        ClientUtils.waitForInstantProducerClientSuccess(testStorage);

        LOGGER.info("Checking if connector is able to consume messages");
        final String connectorPodName = kubeClient().listPods(testStorage.getNamespaceName(), Collections.singletonMap(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND)).get(0).getMetadata().getName();
        KafkaConnectUtils.waitForMessagesInKafkaConnectFileSink(testStorage.getNamespaceName(), connectorPodName, TestConstants.DEFAULT_SINK_FILE_PATH, testStorage.getMessageCount());
    }

    @Tag(CONNECTOR_OPERATOR)
    @ParallelNamespaceTest
    @TestDoc(
        description = @Desc("Test verifying that Kafka Connect can authenticate with SCRAM-SHA-512 using a username with special characters and length exceeding typical constraints."),
        steps = {
            @Step(value = "Create resource with Node Pools", expected = "Node Pools created successfully"),
            @Step(value = "Create NodePools using resourceManager based on the configuration", expected = "NodePools for broker and controller are created or not based on configuration"),
            @Step(value = "Deploy Kafka cluster with SCRAM-SHA-512 authentication", expected = "Kafka cluster deployed with specified authentications"),
            @Step(value = "Create Kafka Topic", expected = "Topic created successfully"),
            @Step(value = "Create Kafka SCRAM-SHA-512 user with a weird username", expected = "User created successfully with SCRAM-SHA-512 credentials"),
            @Step(value = "Deploy Kafka Connect with SCRAM-SHA-512 authentication", expected = "Kafka Connect instance deployed and configured with user credentials"),
            @Step(value = "Deploy Kafka Connector", expected = "Kafka Connector deployed and configured successfully"),
            @Step(value = "Send messages using the configured client", expected = "Messages sent successfully"),
            @Step(value = "Verify that connector receives messages", expected = "Messages consumed by the connector and written to the specified sink")
        },
        labels = {
            @Label(value = "connect")
        }
    )
    void testConnectScramShaAuthWithWeirdUserName() {
        final TestStorage testStorage = new TestStorage(ResourceManager.getTestContext());

        // Create weird named user with . and 92 characters which is more than 64 -> SCRAM-SHA
        final String weirdUserName = "jjglmahyijoambryleyxjjglmahy.ijoambryleyxjjglmahyijoambryleyxasdsadasdasdasdasdgasgadfasdad";

        resourceManager.createResourceWithWait(
            NodePoolsConverter.convertNodePoolsIfNeeded(
                KafkaNodePoolTemplates.brokerPool(testStorage.getNamespaceName(), testStorage.getBrokerPoolName(), testStorage.getClusterName(), 3).build(),
                KafkaNodePoolTemplates.controllerPool(testStorage.getNamespaceName(), testStorage.getControllerPoolName(), testStorage.getClusterName(), 3).build()
            )
        );
        resourceManager.createResourceWithWait(KafkaTemplates.kafkaEphemeral(testStorage.getNamespaceName(), testStorage.getClusterName(), 3)
            .editSpec()
                .editKafka()
                    .withListeners(new GenericKafkaListenerBuilder()
                            .withName(TestConstants.TLS_LISTENER_DEFAULT_NAME)
                            .withPort(9093)
                            .withType(KafkaListenerType.INTERNAL)
                            .withTls(true)
                            .withAuth(new KafkaListenerAuthenticationScramSha512())
                            .build())
                .endKafka()
            .endSpec()
            .build());

        resourceManager.createResourceWithWait(KafkaTopicTemplates.topic(testStorage.getNamespaceName(), testStorage.getTopicName(), testStorage.getClusterName()).build());
        resourceManager.createResourceWithWait(KafkaUserTemplates.scramShaUser(testStorage.getNamespaceName(), weirdUserName, testStorage.getClusterName()).build());
        resourceManager.createResourceWithWait(KafkaConnectTemplates.kafkaConnectWithFilePlugin(testStorage.getNamespaceName(), testStorage.getClusterName(), 1)
                .editMetadata()
                    .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
                .endMetadata()
                .editOrNewSpec()
                    .withBootstrapServers(KafkaResources.tlsBootstrapAddress(testStorage.getClusterName()))
                    .withNewKafkaClientAuthenticationScramSha512()
                        .withUsername(weirdUserName)
                        .withPasswordSecret(new PasswordSecretSourceBuilder()
                            .withSecretName(weirdUserName)
                            .withPassword("password")
                            .build())
                    .endKafkaClientAuthenticationScramSha512()
                    .addToConfig("key.converter.schemas.enable", false)
                    .addToConfig("value.converter.schemas.enable", false)
                    .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .withNewTls()
                        .withTrustedCertificates(new CertSecretSourceBuilder()
                                .withCertificate("ca.crt")
                                .withSecretName(KafkaResources.clusterCaCertificateSecretName(testStorage.getClusterName()))
                                .build())
                    .endTls()
                .endSpec()
                .build());
        resourceManager.createResourceWithWait(KafkaConnectorTemplates.kafkaConnector(testStorage.getNamespaceName(), testStorage.getClusterName())
            .editSpec()
                .withClassName("org.apache.kafka.connect.file.FileStreamSinkConnector")
                .addToConfig("topics", testStorage.getTopicName())
                .addToConfig("file", TestConstants.DEFAULT_SINK_FILE_PATH)
            .endSpec()
            .build());

        final KafkaClients clients = ClientUtils.getInstantScramShaOverTlsClientBuilder(testStorage)
            .withUsername(weirdUserName)
            .build();

        LOGGER.info("Checking if user is able to send messages");
        resourceManager.createResourceWithWait(clients.producerScramShaTlsStrimzi(testStorage.getClusterName()));
        ClientUtils.waitForInstantProducerClientSuccess(testStorage);

        LOGGER.info("Checking if connector is able to consume messages");
        final String connectorPodName = kubeClient().listPods(testStorage.getNamespaceName(), Collections.singletonMap(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND)).get(0).getMetadata().getName();
        KafkaConnectUtils.waitForMessagesInKafkaConnectFileSink(testStorage.getNamespaceName(), connectorPodName, TestConstants.DEFAULT_SINK_FILE_PATH, testStorage.getMessageCount());
    }

    @ParallelNamespaceTest
    @Tag(COMPONENT_SCALING)
    @TestDoc(
        description = @Desc("Test to validate scaling KafkaConnect without a connector to zero replicas."),
        steps = {
            @Step(value = "Initialize TestStorage and create namespace", expected = "Namespace and storage initialized"),
            @Step(value = "Create broker and controller node pools", expected = "Node pools created with 3 replicas."),
            @Step(value = "Create ephemeral Kafka cluster", expected = "Kafka cluster created with 3 replicas."),
            @Step(value = "Create KafkaConnect resource with 2 replicas", expected = "KafkaConnect resource created with 2 replicas."),
            @Step(value = "Verify that KafkaConnect has 2 pods", expected = "2 KafkaConnect pods are running."),
            @Step(value = "Scale down KafkaConnect to zero replicas", expected = "KafkaConnect scaled to zero replicas."),
            @Step(value = "Wait for KafkaConnect to be ready", expected = "KafkaConnect is ready with 0 replicas."),
            @Step(value = "Verify that KafkaConnect has 0 pods", expected = "No KafkaConnect pods are running and status is ready.")
        },
        labels = {
            @Label(value = "connect")
        }
    )
    void testScaleConnectWithoutConnectorToZero() {
        final TestStorage testStorage = new TestStorage(ResourceManager.getTestContext());

        resourceManager.createResourceWithWait(
            NodePoolsConverter.convertNodePoolsIfNeeded(
                KafkaNodePoolTemplates.brokerPool(testStorage.getNamespaceName(), testStorage.getBrokerPoolName(), testStorage.getClusterName(), 3).build(),
                KafkaNodePoolTemplates.controllerPool(testStorage.getNamespaceName(), testStorage.getControllerPoolName(), testStorage.getClusterName(), 3).build()
            )
        );
        resourceManager.createResourceWithWait(KafkaTemplates.kafkaEphemeral(testStorage.getNamespaceName(), testStorage.getClusterName(), 3).build());
        resourceManager.createResourceWithWait(KafkaConnectTemplates.kafkaConnectWithFilePlugin(testStorage.getNamespaceName(), testStorage.getClusterName(), 2).build());

        List<Pod> connectPods = kubeClient(testStorage.getNamespaceName()).listPods(Labels.STRIMZI_NAME_LABEL, KafkaConnectResources.componentName(testStorage.getClusterName()));

        assertThat(connectPods.size(), is(2));
        //scale down
        LOGGER.info("Scaling KafkaConnect down to zero");
        KafkaConnectResource.replaceKafkaConnectResourceInSpecificNamespace(testStorage.getClusterName(), kafkaConnect -> kafkaConnect.getSpec().setReplicas(0), testStorage.getNamespaceName());

        KafkaConnectUtils.waitForConnectReady(testStorage.getNamespaceName(), testStorage.getClusterName());
        PodUtils.waitForPodsReady(testStorage.getNamespaceName(), testStorage.getKafkaConnectSelector(), 0, true);

        connectPods = kubeClient(testStorage.getNamespaceName()).listPods(Labels.STRIMZI_NAME_LABEL, KafkaConnectResources.componentName(testStorage.getClusterName()));
        KafkaConnectStatus connectStatus = KafkaConnectResource.kafkaConnectClient().inNamespace(testStorage.getNamespaceName()).withName(testStorage.getClusterName()).get().getStatus();

        assertThat(connectPods.size(), is(0));
        assertThat(connectStatus.getConditions().get(0).getType(), is(Ready.toString()));
    }

    @ParallelNamespaceTest
    @Tag(CONNECTOR_OPERATOR)
    @Tag(COMPONENT_SCALING)
    @TestDoc(
        description = @Desc("Test scaling Kafka Connect with a connector to zero replicas."),
        steps = {
            @Step(value = "Create TestStorage instance", expected = "TestStorage instance is created with context"),
            @Step(value = "Create broker and controller node pools", expected = "Broker and Controller node pools are created"),
            @Step(value = "Create ephemeral Kafka cluster", expected = "Kafka cluster with 3 replicas is created"),
            @Step(value = "Create Kafka Connect with file plugin", expected = "Kafka Connect is created with 2 replicas and file plugin"),
            @Step(value = "Create Kafka Connector", expected = "Kafka Connector is created with necessary configurations"),
            @Step(value = "Check Kafka Connect pods", expected = "There are 2 Kafka Connect pods"),
            @Step(value = "Scale down Kafka Connect to zero", expected = "Kafka Connect is scaled down to 0 replicas"),
            @Step(value = "Wait for Kafka Connect to be ready", expected = "Kafka Connect readiness is verified"),
            @Step(value = "Wait for Kafka Connector to not be ready", expected = "Kafka Connector readiness is verified"),
            @Step(value = "Verify conditions", expected = "Pod size is 0, Kafka Connect is ready, Kafka Connector is not ready due to zero replicas")
        },
        labels = {
            @Label(value = "connect")
        }
    )
    void testScaleConnectWithConnectorToZero() {
        final TestStorage testStorage = new TestStorage(ResourceManager.getTestContext());

        resourceManager.createResourceWithWait(
            NodePoolsConverter.convertNodePoolsIfNeeded(
                KafkaNodePoolTemplates.brokerPool(testStorage.getNamespaceName(), testStorage.getBrokerPoolName(), testStorage.getClusterName(), 3).build(),
                KafkaNodePoolTemplates.controllerPool(testStorage.getNamespaceName(), testStorage.getControllerPoolName(), testStorage.getClusterName(), 3).build()
            )
        );
        resourceManager.createResourceWithWait(KafkaTemplates.kafkaEphemeral(testStorage.getNamespaceName(), testStorage.getClusterName(), 3).build());
        resourceManager.createResourceWithWait(KafkaConnectTemplates.kafkaConnectWithFilePlugin(testStorage.getNamespaceName(), testStorage.getClusterName(), 2)
            .editMetadata()
                .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
            .endMetadata()
            .build());

        resourceManager.createResourceWithWait(KafkaConnectorTemplates.kafkaConnector(testStorage.getNamespaceName(), testStorage.getClusterName())
            .editSpec()
                .withClassName("org.apache.kafka.connect.file.FileStreamSinkConnector")
                .addToConfig("file", TestConstants.DEFAULT_SINK_FILE_PATH)
                .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("topics", testStorage.getTopicName())
            .endSpec()
            .build());

        List<Pod> connectPods = kubeClient(testStorage.getNamespaceName()).listPods(Labels.STRIMZI_NAME_LABEL, KafkaConnectResources.componentName(testStorage.getClusterName()));

        assertThat(connectPods.size(), is(2));
        //scale down
        LOGGER.info("Scaling KafkaConnect down to zero");
        KafkaConnectResource.replaceKafkaConnectResourceInSpecificNamespace(testStorage.getClusterName(), kafkaConnect -> kafkaConnect.getSpec().setReplicas(0), testStorage.getNamespaceName());

        KafkaConnectUtils.waitForConnectReady(testStorage.getNamespaceName(), testStorage.getClusterName());
        PodUtils.waitForPodsReady(testStorage.getNamespaceName(), testStorage.getKafkaConnectSelector(), 0, true);
        KafkaConnectorUtils.waitForConnectorNotReady(testStorage.getNamespaceName(), testStorage.getClusterName());

        connectPods = kubeClient(testStorage.getNamespaceName()).listPods(Labels.STRIMZI_NAME_LABEL, KafkaConnectResources.componentName(testStorage.getClusterName()));
        KafkaConnectStatus connectStatus = KafkaConnectResource.kafkaConnectClient().inNamespace(testStorage.getNamespaceName()).withName(testStorage.getClusterName()).get().getStatus();
        KafkaConnectorStatus connectorStatus = KafkaConnectorResource.kafkaConnectorClient().inNamespace(testStorage.getNamespaceName()).withName(testStorage.getClusterName()).get().getStatus();

        assertThat(connectPods.size(), is(0));
        assertThat(connectStatus.getConditions().get(0).getType(), is(Ready.toString()));
        assertThat(connectorStatus.getConditions().stream().anyMatch(condition -> condition.getType().equals(NotReady.toString())), is(true));
        assertThat(connectorStatus.getConditions().stream().anyMatch(condition -> condition.getMessage().contains("has 0 replicas")), is(true));
    }

    @ParallelNamespaceTest
    @Tag(CONNECTOR_OPERATOR)
    @Tag(COMPONENT_SCALING)
    @TestDoc(
        description = @Desc("This test verifies the scaling functionality of Kafka Connect and Kafka Connector subresources."),
        steps = {
            @Step(value = "Initialize the test storage and create broker and controller pools", expected = "Broker and controller pools are created successfully"),
            @Step(value = "Create NodePools using resourceManager based on the configuration", expected = "NodePools for broker and controller are created or not based on configuration"),
            @Step(value = "Deploy Kafka, Kafka Connect and Kafka Connector resources", expected = "Kafka, Kafka Connect and Kafka Connector resources are deployed successfully"),
            @Step(value = "Scale Kafka Connect subresource", expected = "Kafka Connect subresource is scaled successfully"),
            @Step(value = "Verify Kafka Connect subresource scaling", expected = "Kafka Connect replicas and observed generation are as expected"),
            @Step(value = "Scale Kafka Connector subresource", expected = "Kafka Connector subresource task max is set correctly"),
            @Step(value = "Verify Kafka Connector subresource scaling", expected = "Kafka Connector task max in spec, status and Connect Pods API are as expected")
        },
        labels = {
            @Label(value = "connect")
        }
    )
    void testScaleConnectAndConnectorSubresource() {
        final TestStorage testStorage = new TestStorage(ResourceManager.getTestContext());

        resourceManager.createResourceWithWait(
            NodePoolsConverter.convertNodePoolsIfNeeded(
                KafkaNodePoolTemplates.brokerPool(testStorage.getNamespaceName(), testStorage.getBrokerPoolName(), testStorage.getClusterName(), 3).build(),
                KafkaNodePoolTemplates.controllerPool(testStorage.getNamespaceName(), testStorage.getControllerPoolName(), testStorage.getClusterName(), 3).build()
            )
        );
        resourceManager.createResourceWithWait(KafkaTemplates.kafkaEphemeral(testStorage.getNamespaceName(), testStorage.getClusterName(), 3).build());
        resourceManager.createResourceWithWait(KafkaConnectTemplates.kafkaConnectWithFilePlugin(testStorage.getNamespaceName(), testStorage.getClusterName(), 1)
            .editMetadata()
                .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
            .endMetadata()
            .build());

        resourceManager.createResourceWithWait(KafkaConnectorTemplates.kafkaConnector(testStorage.getNamespaceName(), testStorage.getClusterName())
            .editSpec()
                .withClassName("org.apache.kafka.connect.file.FileStreamSinkConnector")
                .addToConfig("file", TestConstants.DEFAULT_SINK_FILE_PATH)
                .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("topics", testStorage.getTopicName())
            .endSpec()
            .build());

        final int scaleTo = 4;
        final long connectObsGen = KafkaConnectResource.kafkaConnectClient().inNamespace(testStorage.getNamespaceName()).withName(testStorage.getClusterName()).get().getStatus().getObservedGeneration();

        LOGGER.info("-------> Scaling KafkaConnect subresource <-------");
        LOGGER.info("Scaling subresource replicas to {}", scaleTo);
        cmdKubeClient(testStorage.getNamespaceName()).scaleByName(KafkaConnect.RESOURCE_KIND, testStorage.getClusterName(), scaleTo);

        PodUtils.waitForPodsReady(testStorage.getNamespaceName(), testStorage.getKafkaConnectSelector(), scaleTo, true);

        LOGGER.info("Check if replicas is set to {}, observed generation is higher - for spec and status - naming prefix should be same", scaleTo);

        StUtils.waitUntilSupplierIsSatisfied("KafkaConnect expected size (status, replicas, pod count)",
            () -> kubeClient(testStorage.getNamespaceName()).listPods(Labels.STRIMZI_NAME_LABEL, KafkaConnectResources.componentName(testStorage.getClusterName())).size() == scaleTo &&
                KafkaConnectResource.kafkaConnectClient().inNamespace(testStorage.getNamespaceName()).withName(testStorage.getClusterName()).get().getSpec().getReplicas() == scaleTo &&
                KafkaConnectResource.kafkaConnectClient().inNamespace(testStorage.getNamespaceName()).withName(testStorage.getClusterName()).get().getStatus().getReplicas() == scaleTo);

        List<Pod> connectPods = kubeClient(testStorage.getNamespaceName()).listPods(Labels.STRIMZI_NAME_LABEL, KafkaConnectResources.componentName(testStorage.getClusterName()));
        /*
        observed generation should be higher than before scaling -> after change of spec and successful reconciliation,
        the observed generation is increased
        */
        assertThat(connectObsGen < KafkaConnectResource.kafkaConnectClient().inNamespace(testStorage.getNamespaceName()).withName(testStorage.getClusterName()).get().getStatus().getObservedGeneration(), is(true));

        LOGGER.info("-------> Scaling KafkaConnector subresource <-------");
        LOGGER.info("Scaling subresource task max to {}", scaleTo);
        cmdKubeClient(testStorage.getNamespaceName()).scaleByName(KafkaConnector.RESOURCE_KIND, testStorage.getClusterName(), scaleTo);
        KafkaConnectorUtils.waitForConnectorsTaskMaxChange(testStorage.getNamespaceName(), testStorage.getClusterName(), scaleTo);

        LOGGER.info("Check if taskMax is set to {}", scaleTo);
        assertThat(KafkaConnectorResource.kafkaConnectorClient().inNamespace(testStorage.getNamespaceName()).withName(testStorage.getClusterName()).get().getSpec().getTasksMax(), is(scaleTo));
        assertThat(KafkaConnectorResource.kafkaConnectorClient().inNamespace(testStorage.getNamespaceName()).withName(testStorage.getClusterName()).get().getStatus().getTasksMax(), is(scaleTo));

        LOGGER.info("Check taskMax on Connect Pods API");
        for (Pod pod : connectPods) {
            LOGGER.info("Checking taskMax over API for Pod: {}/{}", pod.getMetadata().getNamespace(), pod.getMetadata().getName());
            TestUtils.waitFor(String.format("pod's %s API return tasksMax=%s", pod.getMetadata().getName(), scaleTo),
                TestConstants.GLOBAL_POLL_INTERVAL, TestConstants.GLOBAL_TIMEOUT_SHORT, () -> {
                    JsonObject json = new JsonObject(KafkaConnectorUtils.getConnectorSpecFromConnectAPI(testStorage.getNamespaceName(), pod.getMetadata().getName(), testStorage.getClusterName()));
                    return Integer.parseInt(json.getJsonObject("config").getString("tasks.max")) == scaleTo;
                });
        }
    }

    @ParallelNamespaceTest
    @SuppressWarnings({"checkstyle:MethodLength"})
    @TestDoc(
        description = @Desc("This test verifies that Secrets and ConfigMaps can be mounted as volumes and environment variables in Kafka Connect."),
        steps = {
            @Step(value = "Create Secrets and ConfigMaps", expected = "Secrets and ConfigMaps are created successfully."),
            @Step(value = "Create Kafka environment", expected = "Kafka broker, Kafka Connect, and other resources are deployed successfully."),
            @Step(value = "Create NodePools using resourceManager based on the configuration", expected = "NodePools for broker and controller are created or not based on configuration"),
            @Step(value = "Bind Secrets and ConfigMaps to Kafka Connect", expected = "Secrets and ConfigMaps are bound to Kafka Connect as volumes and environment variables."),
            @Step(value = "Verify environment variables", expected = "Kafka Connect environment variables contain expected values from Secrets and ConfigMaps."),
            @Step(value = "Verify mounted volumes", expected = "Kafka Connect mounted volumes contain expected values from Secrets and ConfigMaps.")
        },
        labels = {
            @Label(value = "connect")
        }
    )
    void testMountingSecretAndConfigMapAsVolumesAndEnvVars() {
        final TestStorage testStorage = new TestStorage(ResourceManager.getTestContext());

        final String secretPassword = "password";
        final String encodedPassword = Base64.getEncoder().encodeToString(secretPassword.getBytes());

        final String secretEnv = "MY_CONNECT_SECRET";
        final String configMapEnv = "MY_CONNECT_CONFIG_MAP";

        final String dotedSecretEnv = "MY_DOTED_CONNECT_SECRET";
        final String dotedConfigMapEnv = "MY_DOTED_CONNECT_CONFIG_MAP";

        final String configMapName = "connect-config-map";
        final String secretName = "connect-secret";

        final String dotedConfigMapName = "connect.config.map";
        final String dotedSecretName = "connect.secret";

        final String configMapKey = "my-key";
        final String secretKey = "my-secret-key";

        final String configMapValue = "my-value";

        Secret connectSecret = new SecretBuilder()
            .withNewMetadata()
                .withName(secretName)
                .withNamespace(testStorage.getNamespaceName())
            .endMetadata()
            .withType("Opaque")
            .addToData(secretKey, encodedPassword)
            .build();

        ConfigMap configMap = new ConfigMapBuilder()
            .editOrNewMetadata()
                .withName(configMapName)
            .endMetadata()
            .addToData(configMapKey, configMapValue)
            .build();

        Secret dotedConnectSecret = new SecretBuilder()
            .withNewMetadata()
                .withName(dotedSecretName)
                .withNamespace(testStorage.getNamespaceName())
            .endMetadata()
            .withType("Opaque")
            .addToData(secretKey, encodedPassword)
            .build();

        ConfigMap dotedConfigMap = new ConfigMapBuilder()
            .editOrNewMetadata()
                .withName(dotedConfigMapName)
            .endMetadata()
            .addToData(configMapKey, configMapValue)
            .build();

        kubeClient(testStorage.getNamespaceName()).createSecret(connectSecret);
        kubeClient(testStorage.getNamespaceName()).createSecret(dotedConnectSecret);

        kubeClient().createConfigMapInNamespace(testStorage.getNamespaceName(), configMap);
        kubeClient().createConfigMapInNamespace(testStorage.getNamespaceName(), dotedConfigMap);

        resourceManager.createResourceWithWait(
            NodePoolsConverter.convertNodePoolsIfNeeded(
                KafkaNodePoolTemplates.brokerPool(testStorage.getNamespaceName(), testStorage.getBrokerPoolName(), testStorage.getClusterName(), 3).build(),
                KafkaNodePoolTemplates.controllerPool(testStorage.getNamespaceName(), testStorage.getControllerPoolName(), testStorage.getClusterName(), 3).build()
            )
        );
        resourceManager.createResourceWithWait(KafkaTemplates.kafkaEphemeral(testStorage.getNamespaceName(), testStorage.getClusterName(), 3).build());
        resourceManager.createResourceWithWait(KafkaConnectTemplates.kafkaConnect(testStorage.getNamespaceName(), testStorage.getClusterName(), 1)
            .editMetadata()
                .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
            .endMetadata()
            .editSpec()
                .withNewExternalConfiguration()
                    .addNewVolume()
                        .withName(secretName)
                        .withSecret(new SecretVolumeSourceBuilder().withSecretName(secretName).build())
                    .endVolume()
                    .addNewVolume()
                        .withName(configMapName)
                        .withConfigMap(new ConfigMapVolumeSourceBuilder().withName(configMapName).build())
                    .endVolume()
                    .addNewVolume()
                        .withName(dotedSecretName)
                        .withSecret(new SecretVolumeSourceBuilder().withSecretName(dotedSecretName).build())
                    .endVolume()
                    .addNewVolume()
                        .withName(dotedConfigMapName)
                        .withConfigMap(new ConfigMapVolumeSourceBuilder().withName(dotedConfigMapName).build())
                    .endVolume()
                    .addNewEnv()
                        .withName(secretEnv)
                        .withNewValueFrom()
                            .withSecretKeyRef(
                                new SecretKeySelectorBuilder()
                                    .withKey(secretKey)
                                    .withName(connectSecret.getMetadata().getName())
                                    .withOptional(false)
                                    .build())
                        .endValueFrom()
                    .endEnv()
                    .addNewEnv()
                        .withName(configMapEnv)
                        .withNewValueFrom()
                            .withConfigMapKeyRef(
                                new ConfigMapKeySelectorBuilder()
                                    .withKey(configMapKey)
                                    .withName(configMap.getMetadata().getName())
                                    .withOptional(false)
                                    .build())
                        .endValueFrom()
                    .endEnv()
                    .addNewEnv()
                        .withName(dotedSecretEnv)
                        .withNewValueFrom()
                            .withSecretKeyRef(
                                new SecretKeySelectorBuilder()
                                    .withKey(secretKey)
                                    .withName(dotedConnectSecret.getMetadata().getName())
                                    .withOptional(false)
                                    .build())
                        .endValueFrom()
                    .endEnv()
                    .addNewEnv()
                        .withName(dotedConfigMapEnv)
                        .withNewValueFrom()
                            .withConfigMapKeyRef(
                                new ConfigMapKeySelectorBuilder()
                                    .withKey(configMapKey)
                                    .withName(dotedConfigMap.getMetadata().getName())
                                    .withOptional(false)
                                    .build())
                        .endValueFrom()
                    .endEnv()
                .endExternalConfiguration()
            .endSpec()
            .build());

        String connectPodName = kubeClient(testStorage.getNamespaceName()).listPodsByPrefixInName(KafkaConnectResources.componentName(testStorage.getClusterName())).get(0).getMetadata().getName();

        LOGGER.info("Check if the ENVs contains desired values");
        assertThat(cmdKubeClient(testStorage.getNamespaceName()).execInPod(connectPodName, "/bin/bash", "-c", "printenv " + secretEnv).out().trim(), equalTo(secretPassword));
        assertThat(cmdKubeClient(testStorage.getNamespaceName()).execInPod(connectPodName, "/bin/bash", "-c", "printenv " + configMapEnv).out().trim(), equalTo(configMapValue));
        assertThat(cmdKubeClient(testStorage.getNamespaceName()).execInPod(connectPodName, "/bin/bash", "-c", "printenv " + dotedSecretEnv).out().trim(), equalTo(secretPassword));
        assertThat(cmdKubeClient(testStorage.getNamespaceName()).execInPod(connectPodName, "/bin/bash", "-c", "printenv " + dotedConfigMapEnv).out().trim(), equalTo(configMapValue));

        LOGGER.info("Check if volumes contains desired values");
        assertThat(
            cmdKubeClient(testStorage.getNamespaceName()).execInPod(connectPodName, "/bin/bash", "-c", "cat external-configuration/" + configMapName + "/" + configMapKey).out().trim(),
            equalTo(configMapValue)
        );
        assertThat(
            cmdKubeClient(testStorage.getNamespaceName()).execInPod(connectPodName, "/bin/bash", "-c", "cat external-configuration/" + secretName + "/" + secretKey).out().trim(),
            equalTo(secretPassword)
        );
        assertThat(
            cmdKubeClient(testStorage.getNamespaceName()).execInPod(connectPodName, "/bin/bash", "-c", "cat external-configuration/" + dotedConfigMapName + "/" + configMapKey).out().trim(),
            equalTo(configMapValue)
        );
        assertThat(
            cmdKubeClient(testStorage.getNamespaceName()).execInPod(connectPodName, "/bin/bash", "-c", "cat external-configuration/" + dotedSecretName + "/" + secretKey).out().trim(),
            equalTo(secretPassword)
        );
    }

    @ParallelNamespaceTest
    // changing the password in Secret should cause the RU of connect pod
    @TestDoc(
        description = @Desc("Verifies Kafka Connect functionality when SCRAM-SHA authentication password is changed and the component is rolled."),
        steps = {
            @Step(value = "Create NodePools using resourceManager based on the configuration", expected = "NodePools for broker and controller are created or not based on configuration"),
            @Step(value = "Create Kafka cluster with SCRAM-SHA authentication", expected = "Kafka cluster is created with SCRAM-SHA authentication enabled"),
            @Step(value = "Create a Kafka user with SCRAM-SHA authentication", expected = "Kafka user with SCRAM-SHA authentication is created"),
            @Step(value = "Deploy Kafka Connect with the created user credentials", expected = "Kafka Connect is deployed successfully"),
            @Step(value = "Update the SCRAM-SHA user password and reconfigure Kafka Connect", expected = "Kafka Connect is reconfigured with the new password"),
            @Step(value = "Verify Kafka Connect continues to function after rolling update", expected = "Kafka Connect remains functional and REST API is available")
        },
        labels = {
            @Label(value = "connect")
        }
    )
    void testKafkaConnectWithScramShaAuthenticationRolledAfterPasswordChanged() {
        final TestStorage testStorage = new TestStorage(ResourceManager.getTestContext());

        resourceManager.createResourceWithWait(
            NodePoolsConverter.convertNodePoolsIfNeeded(
                KafkaNodePoolTemplates.brokerPool(testStorage.getNamespaceName(), testStorage.getBrokerPoolName(), testStorage.getClusterName(), 3).build(),
                KafkaNodePoolTemplates.controllerPool(testStorage.getNamespaceName(), testStorage.getControllerPoolName(), testStorage.getClusterName(), 3).build()
            )
        );
        resourceManager.createResourceWithWait(KafkaTemplates.kafkaEphemeral(testStorage.getNamespaceName(), testStorage.getClusterName(), 3)
                .editSpec()
                .editKafka()
                .withListeners(new GenericKafkaListenerBuilder()
                        .withName(TestConstants.PLAIN_LISTENER_DEFAULT_NAME)
                        .withPort(9092)
                        .withType(KafkaListenerType.INTERNAL)
                        .withTls(false)
                        .withAuth(new KafkaListenerAuthenticationScramSha512())
                        .build())
                .endKafka()
                .endSpec()
                .build());

        Secret passwordSecret = new SecretBuilder()
                .withNewMetadata()
                    .withName("custom-pwd-secret")
                    .withNamespace(testStorage.getNamespaceName())
                .endMetadata()
                .addToStringData("pwd", "completely_secret_password_long_enough_for_fips")
                .build();

        kubeClient(testStorage.getNamespaceName()).createSecret(passwordSecret);

        KafkaUser kafkaUser =  KafkaUserTemplates.scramShaUser(testStorage.getNamespaceName(), testStorage.getKafkaUsername(), testStorage.getClusterName())
                .editSpec()
                    .withNewKafkaUserScramSha512ClientAuthentication()
                        .withNewPassword()
                            .withNewValueFrom()
                                .withNewSecretKeyRef("pwd", "custom-pwd-secret", false)
                            .endValueFrom()
                        .endPassword()
                    .endKafkaUserScramSha512ClientAuthentication()
                .endSpec()
                .build();

        resourceManager.createResourceWithWait(kafkaUser);

        resourceManager.createResourceWithWait(KafkaTopicTemplates.topic(testStorage.getNamespaceName(), testStorage.getTopicName(), testStorage.getClusterName()).build());
        resourceManager.createResourceWithWait(KafkaConnectTemplates.kafkaConnect(testStorage.getNamespaceName(), testStorage.getClusterName(), 1)
                .withNewSpec()
                    .withBootstrapServers(KafkaResources.plainBootstrapAddress(testStorage.getClusterName()))
                    .withNewKafkaClientAuthenticationScramSha512()
                        .withUsername(testStorage.getKafkaUsername())
                        .withPasswordSecret(new PasswordSecretSourceBuilder()
                            .withSecretName(testStorage.getKafkaUsername())
                            .withPassword("password")
                            .build())
                    .endKafkaClientAuthenticationScramSha512()
                    .addToConfig("key.converter.schemas.enable", false)
                    .addToConfig("value.converter.schemas.enable", false)
                    .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .withVersion(Environment.ST_KAFKA_VERSION)
                    .withReplicas(1)
                .endSpec()
                .build());

        final String kafkaConnectPodName = kubeClient(testStorage.getNamespaceName()).listPodsByPrefixInName(KafkaConnectResources.componentName(testStorage.getClusterName())).get(0).getMetadata().getName();

        KafkaConnectUtils.waitUntilKafkaConnectRestApiIsAvailable(testStorage.getNamespaceName(), kafkaConnectPodName);

        Map<String, String> connectSnapshot = PodUtils.podSnapshot(testStorage.getNamespaceName(), testStorage.getKafkaConnectSelector());
        Secret newPasswordSecret = new SecretBuilder()
                .withNewMetadata()
                    .withName("new-custom-pwd-secret")
                    .withNamespace(testStorage.getNamespaceName())
                .endMetadata()
                .addToStringData("pwd", "completely_different_secret_password_long_enough_for_fips")
                .build();

        kubeClient(testStorage.getNamespaceName()).createSecret(newPasswordSecret);

        kafkaUser = KafkaUserTemplates.scramShaUser(testStorage.getNamespaceName(), testStorage.getKafkaUsername(), testStorage.getClusterName())
                .editSpec()
                    .withNewKafkaUserScramSha512ClientAuthentication()
                        .withNewPassword()
                            .withNewValueFrom()
                                .withNewSecretKeyRef("pwd", "new-custom-pwd-secret", false)
                            .endValueFrom()
                        .endPassword()
                    .endKafkaUserScramSha512ClientAuthentication()
                .endSpec()
                .build();

        KafkaUserResource.kafkaUserClient().inNamespace(testStorage.getNamespaceName()).resource(kafkaUser).update();

        RollingUpdateUtils.waitTillComponentHasRolledAndPodsReady(testStorage.getNamespaceName(), testStorage.getKafkaConnectSelector(), 1, connectSnapshot);

        final String kafkaConnectPodNameAfterRU = kubeClient(testStorage.getNamespaceName()).listPodsByPrefixInName(KafkaConnectResources.componentName(testStorage.getClusterName())).get(0).getMetadata().getName();
        KafkaConnectUtils.waitUntilKafkaConnectRestApiIsAvailable(testStorage.getNamespaceName(), kafkaConnectPodNameAfterRU);
    }

    /**
     * This method tests the (FileSink) connector's ability to pause/stop and resume correctly.
     *
     * Firstly the connector is blocked (stopped or paused) using the provided 'blockEditor' consumer.
     * Then, the existing messages in the FileSink file are cleared, so in next step any new message present would indicate connector running again.
     * New messages are produced to the topic, but due to the connector being blocked, these messages should not appear in the FileSink file.
     * Finally, unblocking the connector using the 'unblockEditor' consumer, and the test checks if new messages appear in the FileSink file.
     *
     * @param testStorage         contains configuration.
     * @param kafkaConnectPodName The name of the Kafka Connect Pod.
     * @param connectorBlockEditor         A consumer function to apply the block specification on the Kafka Connector.
     * @param connectorUnblockEditor       A consumer function to apply the unblock specification on the Kafka Connector.
     *
     * @throws Exception if messages produced during FileSink KafkaConnector being stopped/paused do not appear in destination file in reasonable time.
     */
    void verifySinkConnectorByBlockAndUnblock(TestStorage testStorage, String kafkaConnectPodName, Consumer<KafkaConnector> connectorBlockEditor, Consumer<KafkaConnector> connectorUnblockEditor) {
        LOGGER.info("Blocking KafkaConnector: {}", testStorage.getClusterName());
        KafkaConnectorResource.replaceKafkaConnectorResourceInSpecificNamespace(testStorage.getClusterName(), connectorBlockEditor, testStorage.getNamespaceName());

        LOGGER.info("Clearing FileSink file to check if KafkaConnector will be really paused/stopped");
        KafkaConnectUtils.clearFileSinkFile(testStorage.getNamespaceName(), kafkaConnectPodName, TestConstants.DEFAULT_SINK_FILE_PATH);

        // messages need to be produced for each run (although there are more messages in topic eventually, sink will not copy messages it copied previously (before clearing them)
        LOGGER.info("Producing new messages which are to be watched KafkaConnector once it is resumed");
        final KafkaClients kafkaClients = ClientUtils.getInstantPlainClients(testStorage);
        resourceManager.createResourceWithWait(kafkaClients.producerStrimzi(), kafkaClients.consumerStrimzi());
        ClientUtils.waitForInstantClientSuccess(testStorage);

        LOGGER.info("Because KafkaConnector is blocked, no messages should appear to FileSink file");
        assertThrows(Exception.class, () -> KafkaConnectUtils.waitForMessagesInKafkaConnectFileSink(testStorage.getNamespaceName(), kafkaConnectPodName, TestConstants.DEFAULT_SINK_FILE_PATH, testStorage.getMessageCount()));

        LOGGER.info("Unblocking KafkaConnector, newly produced messages should again appear to FileSink file");
        KafkaConnectorResource.replaceKafkaConnectorResourceInSpecificNamespace(testStorage.getClusterName(), connectorUnblockEditor, testStorage.getNamespaceName());
        KafkaConnectorUtils.waitForConnectorReady(testStorage.getNamespaceName(), testStorage.getClusterName());
        KafkaConnectUtils.waitForMessagesInKafkaConnectFileSink(testStorage.getNamespaceName(), kafkaConnectPodName, TestConstants.DEFAULT_SINK_FILE_PATH, testStorage.getMessageCount());
    }

    @BeforeAll
    void setUp() {
        clusterOperator = clusterOperator
            .defaultInstallation()
            .withOperationTimeout(TestConstants.CO_OPERATION_TIMEOUT_MEDIUM)
            .createInstallation()
            .runInstallation();
    }
}
