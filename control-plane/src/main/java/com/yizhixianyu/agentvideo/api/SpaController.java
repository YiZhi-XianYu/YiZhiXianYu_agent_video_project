package com.yizhixianyu.agentvideo.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {

    @GetMapping({
        "/auth",
        "/workflows",
        "/audit",
        "/projects/{projectId}",
        "/projects/{projectId}/audit",
        "/projects/{projectId}/runs/{runId}",
        "/projects/{projectId}/runs/{runId}/versions"
    })
    public String frontend() {
        return "forward:/index.html";
    }
}
