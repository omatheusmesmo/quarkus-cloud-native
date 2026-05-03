package dev.omatheusmesmo.demo.resource;

import dev.omatheusmesmo.demo.dto.WebhookRequest;
import dev.omatheusmesmo.demo.dto.WebhookResponse;
import dev.omatheusmesmo.demo.entity.Webhook;
import io.quarkus.logging.Log;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;

@Path("/api/webhooks")
public class WebhookResource {

    static final String MODE = "blocking";

    @GET
    public List<WebhookResponse> list() {
        return Webhook.listAll().stream()
            .map(w -> WebhookResponse.from((Webhook) w))
            .toList();
    }

    @GET
    @Path("/{id}")
    public Response getById(Long id) {
        return Webhook.findByIdOptional(id)
            .map(w -> Response.ok(WebhookResponse.from((Webhook) w)).build())
            .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @POST
    @Transactional
    public Response create(@Valid WebhookRequest request) {
        Log.infof("[%s] Received webhook from %s: %s", MODE, request.source(), request.eventType());
        Webhook webhook = new Webhook();
        webhook.source = request.source();
        webhook.eventType = request.eventType();
        webhook.payload = request.payload();
        webhook.receivedAt = Instant.now();
        webhook.processingMode = MODE;
        webhook.persist();
        return Response.status(Response.Status.CREATED).entity(WebhookResponse.from(webhook)).build();
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(Long id) {
        boolean deleted = Webhook.deleteById(id);
        return deleted ? Response.noContent().build() : Response.status(Response.Status.NOT_FOUND).build();
    }
}
