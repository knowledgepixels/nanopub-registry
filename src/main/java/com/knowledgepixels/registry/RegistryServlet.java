package com.knowledgepixels.registry;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class RegistryServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	//private Logger logger = LoggerFactory.getLogger(this.getClass());

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		try {
			setGeneralHeaders(resp);
			ServerRequest r = new ServerRequest(req);
			MainPage.show(r, resp);
		} finally {
			resp.getOutputStream().close();
			req.getInputStream().close();
		}
		//check();
	}

	private void setGeneralHeaders(HttpServletResponse resp) {
		resp.setHeader("Access-Control-Allow-Origin", "*");
	}

}