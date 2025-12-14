package heymary.co.integrations.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class TreezIntegrationViewController {

    @GetMapping("/treez")
    public String treezIntegration() {
        return "redirect:/treez.html";
    }
}

