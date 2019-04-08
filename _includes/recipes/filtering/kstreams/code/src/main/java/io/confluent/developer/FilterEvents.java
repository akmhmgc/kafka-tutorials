package io.confluent.developer;

import io.confluent.developer.avro.User;
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

public class FilterEvents {

    public Properties getEnvProperties() throws IOException {
        Properties props = new Properties();
        ClassLoader classloader = Thread.currentThread().getContextClassLoader();
        InputStream is = classloader.getResourceAsStream("config.properties");
        props.load(is);
        is.close();

        return props;
    }

    public Properties buildStreamsProperties(Properties envProps) {
        Properties props = new Properties();

        props.put(StreamsConfig.APPLICATION_ID_CONFIG, envProps.getProperty("application.id"));
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, envProps.getProperty("bootstrap.servers"));
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.Long().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);
        props.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, envProps.getProperty("schema.registry.url"));

        return props;
    }

    public Topology buildTopology(Properties envProps) {
        final StreamsBuilder builder = new StreamsBuilder();

        final String inputTopic = envProps.getProperty("input.topic.name");
        final String outputTopic = envProps.getProperty("output.topic.name");

        builder.<Long, User>stream(inputTopic)
                .filter((key, user) -> user.getFavoriteColor().equals("green"))
                .to(outputTopic);

        return builder.build();
    }

    public void createTopics(Properties envProps) {
        AdminClient client = AdminClient.create(new HashMap<String, Object>() {{
            put("bootstrap.servers", envProps.getProperty("bootstrap.servers"));
        }});

        List<NewTopic> topics = new ArrayList<NewTopic>() {{
            add(new NewTopic(
                    envProps.getProperty("input.topic.name"),
                    Integer.parseInt(envProps.getProperty("input.topic.partitions")),
                    Short.parseShort(envProps.getProperty("input.topic.replication.factor"))));

            add(new NewTopic(
                    envProps.getProperty("output.topic.name"),
                    Integer.parseInt(envProps.getProperty("output.topic.partitions")),
                    Short.parseShort(envProps.getProperty("output.topic.replication.factor"))));
        }};

        client.createTopics(topics);
        client.close();
    }

    public static void main(String[] args) throws Exception {
        FilterEvents fe = new FilterEvents();
        Properties envProps = fe.getEnvProperties();
        Properties streamProps = fe.buildStreamsProperties(envProps);
        Topology topology = fe.buildTopology(envProps);

        fe.createTopics(envProps);

        final KafkaStreams streams = new KafkaStreams(topology, streamProps);
        final CountDownLatch latch = new CountDownLatch(1);

        // Attach shutdown handler to catch Control-C.
        Runtime.getRuntime().addShutdownHook(new Thread("streams-shutdown-hook") {
            @Override
            public void run() {
                streams.close();
                latch.countDown();
            }
        });

        try {
            streams.start();
            latch.await();
        } catch (Throwable e) {
            System.exit(1);
        }
        System.exit(0);
    }
}
