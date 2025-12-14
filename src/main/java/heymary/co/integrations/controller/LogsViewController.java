package heymary.co.integrations.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LogsViewController {

    @GetMapping("/")
    public String index() {
        return "redirect:/logs.html";
    }

    @GetMapping("/logs")
    public String logs() {
        return "redirect:/logs.html";
    }
}

