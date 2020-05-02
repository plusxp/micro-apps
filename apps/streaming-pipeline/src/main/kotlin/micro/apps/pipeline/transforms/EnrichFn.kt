package micro.apps.pipeline.transforms

import java.util.regex.Pattern
import micro.apps.model.Person
import mu.KotlinLogging
import org.apache.beam.sdk.metrics.Metrics
import org.apache.beam.sdk.transforms.DoFn
// import org.apache.beam.vendor.grpc.v1p26p0.io.grpc.ManagedChannel;
// import org.apache.beam.vendor.grpc.v1p26p0.io.grpc.ManagedChannelBuilder;
// java.util.concurrent.ExecutorService;

private val logger = KotlinLogging.logger {}
public class EnrichFn(pattern: String) : DoFn<Person, Person>() {

    private val filter: Pattern = Pattern.compile(pattern)

    private val enrichedPersons = Metrics.counter(EnrichFn::class.java, "enrichedPersons")
    private val failedPersons = Metrics.counter(EnrichFn::class.java, "failedPersons")

    @StartBundle
    fun setup() {
        // gson = Gson()
    }
    @ProcessElement
    fun processElement(c: ProcessContext) {
        try {
            val inPerson = c.element()
            c.output(inPerson.copy(email = "decrypted email"))
            enrichedPersons.inc()
        } catch (e: Exception) {
            logger.error(e) { "error  message..." }
            failedPersons.inc()
        }
    }
}
