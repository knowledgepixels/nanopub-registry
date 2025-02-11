package com.knowledgepixels.registry;

import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.io.IOUtils;

import com.mongodb.client.ClientSession;

import io.vertx.ext.web.RoutingContext;

public class ResourcePage extends Page {

	public static void show(RoutingContext context, String resourceName, String resourceType) {
		ResourcePage page;
		try (ClientSession s = RegistryDB.getClient().startSession()) {
			s.startTransaction();
			page = new ResourcePage(s, context, resourceName, resourceType);
			page.show();
		} catch (IOException ex) {
			ex.printStackTrace();
		} finally {
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
		getContext().response().putHeader("Content-Type", resourceType);
		InputStream in = null;
		BufferOutputStream out = null;
		try {
			in = MainVerticle.class.getResourceAsStream(resourceName);
			out = new BufferOutputStream();
			IOUtils.copy(in, out);
			getContext().response().write(out.getBuffer());
		} finally {
			if (in != null) in.close();
			if (out != null) out.close();
		}
	}

}
