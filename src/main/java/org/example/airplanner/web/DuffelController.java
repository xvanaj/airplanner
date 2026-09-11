package org.example.airplanner.web;

import jakarta.servlet.http.HttpSession;
import org.example.airplanner.duffel.DuffelClient;
import org.example.airplanner.duffel.DuffelException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.Map;
import java.util.UUID;

@Controller
public class DuffelController {
    private static final String CSRF = "duffelCsrf";
    private final DuffelClient client;
    public DuffelController(DuffelClient client) { this.client = client; }

    @GetMapping("/duffel")
    public String page(Model model, HttpSession session, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (!model.containsAttribute("form")) model.addAttribute("form", new DuffelForm());
        model.addAttribute("provider", client.status());
        model.addAttribute("csrf", token(session));
        return "duffel";
    }

    // PRG avoids repeating a potentially billable request on refresh or Back.
    @PostMapping("/duffel")
    public String search(@ModelAttribute("form") DuffelForm form, BindingResult binding,
                         @RequestParam(defaultValue = "") String csrf, HttpSession session,
                         RedirectAttributes redirect) {
        redirect.addFlashAttribute("form", form);
        try {
            checkToken(session, csrf);
            if (binding.hasErrors()) throw new IllegalArgumentException("Check the number of adults and connections.");
            redirect.addFlashAttribute("result", client.search(form.toSearch()));
        } catch (IllegalArgumentException | DuffelException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/duffel";
    }

    @GetMapping("/api/duffel/status")
    @ResponseBody
    public ResponseEntity<?> status(HttpSession session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("provider", client.status(), "csrfToken", token(session)));
    }

    /** Fetch status first, retain its session cookie, then send its token in X-CSRF-Token. */
    @PostMapping(value = "/api/duffel/search", consumes = "application/json", produces = "application/json")
    @ResponseBody
    public ResponseEntity<?> api(@RequestBody DuffelForm form,
                                 @RequestHeader(value = "X-CSRF-Token", defaultValue = "") String csrf,
                                 HttpSession session) {
        try {
            checkToken(session, csrf);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(client.search(form.toSearch()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("error", e.getMessage()));
        } catch (DuffelException e) {
            return ResponseEntity.status(e.status()).cacheControl(CacheControl.noStore()).body(Map.of("error", e.getMessage()));
        }
    }

    private static String token(HttpSession session) {
        synchronized (session) {
            if (session.getAttribute(CSRF) == null) session.setAttribute(CSRF, UUID.randomUUID().toString());
            return (String) session.getAttribute(CSRF);
        }
    }

    private static void checkToken(HttpSession session, String supplied) {
        Object expected = session.getAttribute(CSRF);
        if (expected == null || !expected.equals(supplied)) {
            throw new DuffelException(403, "Reload the Duffel search page before searching.");
        }
    }
}
