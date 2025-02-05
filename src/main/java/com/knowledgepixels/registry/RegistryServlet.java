package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.has;

import java.io.IOException;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.extra.server.PublishNanopub;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.trustyuri.TrustyUriUtils;

public class RegistryServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	@Override
	public void init() throws ServletException {
		super.init();

		RegistryDB.init();

		new Thread(() -> {
			Task.runTasks();
		}).start();
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		try {
			setGeneralHeaders(resp);
			ServerRequest r = new ServerRequest(req);
			if (r.isEmpty()) {
				MainPage.show(r, resp);
			} else if (r.getRequestString().matches("/list(/.*)?")) {
				ListPage.show(r, resp);
			} else if (r.getRequestString().matches("/agents?")) {
				ListPage.show(r, resp);
			} else if (r.getRequestString().matches("/agentAccounts")) {
				ListPage.show(r, resp);
			} else if (r.getRequestString().matches("/latestNanopubs")) {
				ListPage.show(r, resp);
			} else if (r.getRequestString().matches("/np(/.*)?")) {
				NanopubPage.show(r, resp);
			} else if (r.getRequestString().matches("/debug/.*")) {
				DebugPage.show(r, resp);
			} else if (r.getFullRequest().equals("/style/plain.css")) {
				ResourcePage.show(r, resp, "style.css", "text/css");
			}
		} catch (Exception ex) {
			ex.printStackTrace();
			throw ex;
		} finally {
			resp.getOutputStream().close();
			req.getInputStream().close();
		}
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		try {
			ServerRequest r = new ServerRequest(req);
			if (r.isEmpty()) {
				Nanopub np = null;
				try {
					np = new NanopubImpl(req.getInputStream(), Rio.getParserFormatForMIMEType(req.getContentType()).orElse(RDFFormat.TRIG));
					if (np != null) {
						String ac = TrustyUriUtils.getArtifactCode(np.getUri().toString());
						if (has("nanopubs", ac)) {
							System.err.println("POST: known nanopub " + ac);
						} else {
							System.err.println("POST: new nanopub " + ac);

							// TODO Run checks here whether we want to register this nanopub (considering quotas etc.)
							NanopubLoader.simpleLoad(np);

							// Here we publish it also to the first-generation services, so they know about it too:
							// TODO Remove this at some point
							PublishNanopub.publish(np);
						}
					}
				} catch (Exception ex) {
					resp.sendError(400, "Error processing nanopub: " + ex.getMessage());
				}
			} else {
				resp.sendError(400, "Invalid request: " + r.getFullRequest());
			}
		} finally {
			resp.getOutputStream().close();
			req.getInputStream().close();
		}
	}

	private void setGeneralHeaders(HttpServletResponse resp) {
		resp.setHeader("Access-Control-Allow-Origin", "*");
	}

}