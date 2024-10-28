package com.knowledgepixels.registry;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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
			} else if (r.getFullRequest().matches("/list(/.*)?")) {
				ListPage.show(r, resp);
			} else if (r.getFullRequest().matches("/np(/.*)?")) {
				NanopubPage.show(r, resp);
			} else if (r.getFullRequest().equals("/style/plain.css")) {
				ResourcePage.show(r, resp, "style.css", "text/css");
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