package tk.tcomad.unibot.endpoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.transaction.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.keycloak.KeycloakSecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.ModelAndView;
import tk.tcomad.unibot.client.EducationAppClient;
import tk.tcomad.unibot.client.KeycloakClient;
import tk.tcomad.unibot.dto.keycloak.AuthTokenRequest;
import tk.tcomad.unibot.entity.BotUser;
import tk.tcomad.unibot.entity.LoginSession;
import tk.tcomad.unibot.repository.BotUserRepository;
import tk.tcomad.unibot.repository.LoginSessionRepository;
import tk.tcomad.unibot.telegram.TelegramBot;

@Controller
@RequiredArgsConstructor
public class AuthEndpoint {

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

    @Value("${spring.security.oauth2.client.provider.keycloak.authorization-uri}")
    private String authUri;
    @Value("${keycloak.resource}")
    private String client;
    @Value("${user.login.redirect.uri}")
    private String redirectUri;
    @Value("${bot.key}")
    private String key;
    @Value("${keycloak.credentials.secret}")
    private String clientSecret;

    private final BotUserRepository botUserRepository;
    private final TelegramBot telegramBot;
    private final KeycloakClient keycloakClient;
    private final EducationAppClient studentsClient;
    private final LoginSessionRepository loginSessionRepository;

    @GetMapping("/login")
    @Transactional
    public void login(HttpServletResponse httpServletResponse, @RequestParam Map<String, String> request) {
        checkAuth(request);

        var loginSession = new LoginSession(UUID.randomUUID().toString(),
                                            request.get("id"),
                                            request.get("username"),
                                            request.get("photo_url"),
                                            null);

        loginSessionRepository.deleteAllByChatId(request.get("id"));
        loginSessionRepository.save(loginSession);

        httpServletResponse.setHeader("Location", constructLoginUri(loginSession.getStateToken()));
        httpServletResponse.setStatus(302);
    }

    @GetMapping("/token")
    public String token(Model model, @RequestParam String state, @RequestParam String code) {
        var session = loginSessionRepository.findById(state).orElseThrow();
        var token = keycloakClient.getToken(new AuthTokenRequest(code,
                                                                 redirectUri + "/token",
                                                                 client,
                                                                 "authorization_code",
                                                                 clientSecret));

        session.setToken(token.getAccess_token());
        loginSessionRepository.save(session);

        model.addAttribute("username", session.getUsername());
        model.addAttribute("photo_url", session.getAvatarUrl());
        model.addAttribute("token", token.getAccess_token());
        model.addAttribute("userLink", "https://t.me/" + session.getUsername());
        return "login.html";
    }

    @GetMapping("/complete")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void complete(HttpServletRequest request) {
        var context = (KeycloakSecurityContext) request.getAttribute(KeycloakSecurityContext.class.getName());
        var token = context.getToken();
        var tokenString = context.getTokenString();
        if (!Objects.equals(token.getIssuedFor(), client)) {
            throw new RuntimeException();
        }

        var session = loginSessionRepository.findLoginSessionByToken(tokenString);
        Objects.requireNonNull(session);

        try {
            var educationAppUser = studentsClient.getUser(token.getSubject());
            Objects.requireNonNull(educationAppUser);
            Objects.requireNonNull(educationAppUser.getStudyGroupId());

            var botUser = new BotUser(Long.parseLong(session.getChatId()), educationAppUser.getStudyGroupId());
            botUserRepository.save(botUser);
            loginSessionRepository.delete(session);

            telegramBot.onLoginComplete(Long.parseLong(session.getChatId()), educationAppUser.getFirstName());
        } catch (Exception ignored) {
            loginSessionRepository.delete(session);

            telegramBot.onLoginFail(Long.parseLong(session.getChatId()));
        }
    }

    @GetMapping("/close")
    public ModelAndView close() {
        ModelAndView modelAndView = new ModelAndView();
        modelAndView.setViewName("close.html");
        return modelAndView;
    }

    private String constructLoginUri(String state) {
        return new StringBuilder().append(authUri)
                                  .append("?client_id=")
                                  .append(client)
                                  .append("&redirect_uri=")
                                  .append(redirectUri)
                                  .append("/token")
                                  .append("&response_type=code")
                                  .append("&scope=openid")
                                  .append("&state=")
                                  .append(state)
                                  .toString();
    }

    @SneakyThrows
    private void checkAuth(@RequestBody Map<String, String> request) {
        String hash = request.get("hash");
        request.remove("hash");

        String str = request.entrySet().stream()
                            .sorted((a, b) -> a.getKey().compareToIgnoreCase(b.getKey()))
                            .map(kvp -> kvp.getKey() + "=" + kvp.getValue())
                            .collect(Collectors.joining("\n"));

        var spec = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)),
                                     "HmacSHA256");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(spec);

        var result = mac.doFinal(str.getBytes(StandardCharsets.UTF_8));
        String resultStr = bytesToHex(result);

        if (hash.compareToIgnoreCase(resultStr) != 0) {
            throw new RuntimeException();
        }
    }

    public static String bytesToHex(byte[] bytes) {
        var hexChars = new char[bytes.length * 2];
        for (var j = 0; j < bytes.length; j++) {
            var v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }
}