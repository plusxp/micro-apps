package micro.apps.pipeline

/* ktlint-disable no-wildcard-imports */
import com.google.common.flogger.FluentLogger
import com.google.common.flogger.MetadataKey
import micro.apps.core.LogDefinition.Companion.config
import micro.apps.kbeam.PipeBuilder
import micro.apps.kbeam.coder.AvroToPubsubMessage
import micro.apps.kbeam.parDo
import micro.apps.kbeam.split
import micro.apps.kbeam.toList
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
import org.apache.beam.runners.dataflow.util.TimeUtil
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO
import org.apache.beam.sdk.io.gcp.pubsub.PubsubOptions
import org.apache.beam.sdk.options.*
import org.apache.beam.sdk.transforms.Create
import org.apache.beam.sdk.transforms.MapElements
import org.apache.beam.sdk.transforms.windowing.AfterWatermark
import org.apache.beam.sdk.transforms.windowing.FixedWindows
import org.apache.beam.sdk.transforms.windowing.Window
import org.joda.time.Duration

/* ktlint-enable no-wildcard-imports */

interface MyStreamingOptions : ApplicationNameOptions, PipelineOptions, StreamingOptions, PubsubOptions, GcpOptions {
    @get:Description("""The Cloud Pub/Sub topic to read from.
        The name should be in the format of projects/<project-id>/topics/<topic-name>.""")
    @get:Default.String("projects/my-project-id/topics/streaming-input")
    @get:Validation.Required
    var inputTopic: ValueProvider<String>

    @get:Description("""The Cloud Pub/Sub topic to publish to.
        The name should be in the format of projects/<project-id>/topics/<topic-name>.""")
    @get:Default.String("projects/my-project-id/topics/streaming-output")
    @get:Validation.Required
    var outputTopic: ValueProvider<String>

    @get:Description(
        """The window duration in which data will be written. Defaults to 5m.
                Allowed formats are:
                Ns (for seconds, example: 5s),
                Nm (for minutes, example: 12m),
                Nh (for hours, example: 2h).")""")
    @get:Default.String("300s")
    var windowDuration: String
}

/**
 * showcase side-input and split
 */
object StreamingPipeline {
    @JvmStatic
    private val logger: FluentLogger = FluentLogger.forEnclosingClass().config()

    @JvmStatic
    fun main(args: Array<String>) {

        logger.atConfig().log("My Args: %s", args)

        val (pipe, options) = PipeBuilder.from<MyStreamingOptions>(args)
        options.isStreaming = true
        // set `pubsubRootUrl` via CLI args for development
        // options.pubsubRootUrl = "http://localhost:8085"

        logger.atInfo()
            .with(MetadataKey.single("Runner", String::class.java), options.runner.name)
            .with(MetadataKey.single("JobName", String::class.java), options.jobName)
            .log("Started job with:")

        val schema = Schema.Parser().parse(javaClass.getResourceAsStream("/data/person.avsc"))

        // create dummy `keys` to use as `side input` for decryption
        val keys = pipe.apply(Create.of(listOf("aaa", "bbb"))).toList()

        logger.atInfo()
            .with(MetadataKey.single("schema", Schema::class.java), schema)
            .with(MetadataKey.single("windowDuration", String::class.java), options.windowDuration)
            .with(MetadataKey.single("pubsubRootUrl", String::class.java), options.pubsubRootUrl)
            .log()

        val input = pipe
            .apply("Read new Data from PubSub", PubsubIO.readAvroGenericRecords(schema).fromTopic(options.inputTopic))
            // Batch events into 5 minute windows
            .apply("Batch Events, windowDuration: ${options.windowDuration}", Window.into<GenericRecord>(
                // FixedWindows.of(Duration.standardMinutes(5)))
                FixedWindows.of(TimeUtil.fromCloudDuration(options.windowDuration)))
                .triggering(AfterWatermark.pastEndOfWindow())
                .discardingFiredPanes()
                .withAllowedLateness(Duration.standardSeconds(300)))
            // just for show
            .parDo<GenericRecord, GenericRecord>(
                "decrypt and enrich record",
                sideInputs = listOf(keys)) {
                println(element)
                println(timestamp)
                println(element.schema)
                println("key used to decrypt encrypted field: ${sideInputs[keys][0]}")
                for (field in schema.fields /*element.schema.fields*/) {
                    val fieldKey: String = field.name()
                    println("$fieldKey : ${element.get(fieldKey)}, is encrypted? ${field.getProp("encrypted")}")
                }
                // TODO: may a copy, modify and retun
                // output(element.copy(email = "decrypted email"))
                element
            }

        val (old, young) = input.split {
            println(it)
            true // it.age >= 20
        }

        old.parDo<GenericRecord, Void> {
            println("Old: $element")
        }

        young.parDo<GenericRecord, Void> {
            println("Young: $element")
        }

        input.apply(MapElements.via(AvroToPubsubMessage()))
            // write back to PubSub
            .apply("Write PubSub Events", PubsubIO.writeMessages().to(options.outputTopic))

        pipe.run()
    }
}
