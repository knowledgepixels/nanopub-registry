package com.knowledgepixels.registry;

import com.mongodb.client.ClientSession;
import io.vertx.ext.web.RoutingContext;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

public class ResourcePage extends Page {

    private static final Logger logger = LoggerFactory.getLogger(ResourcePage.class);

    public static void show(RoutingContext context, String resourceName, String resourceType) {
        ResourcePage page;
        try (ClientSession s = RegistryDB.getClient().startSession()) {
            s.startTransaction();
            page = new ResourcePage(s, context, resourceName, resourceType);
            page.show();
        } catch (IOException ex) {
            logger.warn("Failed to show resource {} (type={}): {} ({})", resourceName, resourceType, ex.getMessage(), ex.getClass().getSimpleName(), ex);
        } finally {
            logger.debug("Ending response for resource {} (type={})", resourceName, resourceType);
            context.response().end();
            // TODO Clean-up here?
        }
    }

    private String resourceName, resourceType;

    public ResourcePage(ClientSession mongoSession, RoutingContext context, String resourceName, String resourceType) {
        super(mongoSession, context);
        this.resourceName = resourceName;
        this.resourceType = resourceType;
    }

    public void show() throws IOException {
        setRespContentType(resourceType);
        logger.debug("Preparing to serve resource {} (type={})", resourceName, resourceType);
        InputStream in = null;
        BufferOutputStream out = null;
        try {
            in = MainVerticle.class.getResourceAsStream(resourceName);
            out = new BufferOutputStream();
            IOUtils.copy(in, out);
            getContext().response().write(out.getBuffer());
            logger.info("Served resource {} (type={})", resourceName, resourceType);
        } finally {
            if (in != null) {
                in.close();
            }
            if (out != null) {
                out.close();
            }
        }
    }

}
