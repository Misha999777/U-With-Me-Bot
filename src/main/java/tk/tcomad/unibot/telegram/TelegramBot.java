package tk.tcomad.unibot.telegram;

import static org.apache.commons.lang3.StringUtils.SPACE;
import static org.telegram.abilitybots.api.objects.Locality.USER;
import static org.telegram.abilitybots.api.objects.Privacy.PUBLIC;
import static org.telegram.abilitybots.api.util.AbilityUtils.getChatId;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import lombok.SneakyThrows;
import lombok.extern.java.Log;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.abilitybots.api.bot.AbilityBot;
import org.telegram.abilitybots.api.db.DBContext;
import org.telegram.abilitybots.api.objects.Ability;
import org.telegram.abilitybots.api.objects.Flag;
import org.telegram.abilitybots.api.objects.Reply;
import org.telegram.abilitybots.api.sender.SilentSender;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import tk.tcomad.unibot.client.EducationAppClient;
import tk.tcomad.unibot.dto.uwithme.BotData;
import tk.tcomad.unibot.dto.uwithme.LessonApi;
import tk.tcomad.unibot.repository.BotUserRepository;

@Component
@Log
public class TelegramBot extends AbilityBot {

    @Value("${bot.creatorId}")
    private int creatorId;
    @Value("${bot.firstWeekStart:#{null}}")
    private String firstWeekStart;
    @Value("${user.login.redirect.uri}")
    private String redirectUri;

    private static final String WRONG_COMMAND_MESSAGE = "Неверная команда";
    private static final String NO_ELEMENTS_MESSAGE = "Данные отсутствуют";
    private static final String SELECT_MESSAGE = "Выберите для подробностей";
    private static final String AUTHORIZE_BUTTON_NAME = "Авторизироваться";
    private static final String WELCOME_MESSAGE = "Добро пожаловать";
    private static final String FAIL_MESSAGE = "Вы не являетесь студентом группы";

    private final TelegramBotsApi telegramBotsApi;
    private final BotUserRepository botUserRepository;
    private final EducationAppClient studentsClient;

    public TelegramBot(TelegramBotsApi botsApi,
                       DBContext dbContext,
                       @Value("${bot.key}") String key,
                       @Value("${bot.name}") String name,
                       BotUserRepository botUserRepository,
                       EducationAppClient studentsClient) {
        super(key, name, dbContext);
        this.studentsClient = studentsClient;
        this.botUserRepository = botUserRepository;
        this.telegramBotsApi = botsApi;
    }

    @PostConstruct
    private void postConstruct() throws TelegramApiRequestException {
        this.telegramBotsApi.registerBot(this);
        log.info("Bot registered");
    }

    @Override
    public int creatorId() {
        return creatorId;
    }

    @SuppressWarnings("unused")
    public void setSender(SilentSender silentSender) {
        silent = silentSender;
    }

    @SuppressWarnings("unused")
    public Ability sendWelcome() {
        return Ability
                .builder()
                .name("start")
                .input(0)
                .locality(USER)
                .privacy(PUBLIC)
                .action(ctx -> sendWelcome(ctx.chatId()))
                .build();
    }

    @SuppressWarnings("unused")
    public Reply receiveCallback() {
        Consumer<Update> action = upd -> {
            AnswerCallbackQuery answer = new AnswerCallbackQuery();
            String id = upd.getCallbackQuery().getId();
            answer.setCallbackQueryId(id);
            silent.execute(answer);
            processCallback(getChatId(upd), upd.getCallbackQuery().getData());
        };
        return Reply.of(action, Flag.CALLBACK_QUERY);
    }

    public void onLoginComplete(Long chatId, String userName) {
        sendMessageWithText(chatId, WELCOME_MESSAGE + SPACE + userName);
        sendMenu(chatId);
    }

    public void onLoginFail(Long chatId) {
        sendMessageWithText(chatId, FAIL_MESSAGE);
    }

    private void sendMenu(Long chatId) {
        Map<String, String> commands = Arrays.stream(Command.values())
                                             .map(Command::getMessage)
                                             .collect(LinkedHashMap::new,
                                                      (map, item) -> map.put(item.getLeft(), item.getRight()),
                                                      Map::putAll);

        sendMap(chatId, commands);
    }

    private void sendMap(Long chatId, Map<String, String> map) {
        List<List<InlineKeyboardButton>> keyboard = map.entrySet().stream()
                                                       .map(command -> new InlineKeyboardButton()
                                                               .setText(command.getKey())
                                                               .setCallbackData(command.getValue()))
                                                       .map(List::of)
                                                       .collect(Collectors.toList());
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup().setKeyboard(keyboard);

        sendMessageWithMarkup(chatId, SELECT_MESSAGE, markup);
    }

    private void processCallback(long chatId, String payload) {
        Optional<Command> optionalCommand = Command.fromMessage(payload);
        if (optionalCommand.isPresent()) {
            processCommand(chatId, optionalCommand.get());
        } else {
            String[] splitted = payload.split(SPACE);
            String callbackText = splitted[0];
            String id = splitted[1];
            Optional<Callback> optionalCallback = Callback.fromCallbackText(callbackText);
            optionalCallback.ifPresentOrElse(callback -> processDetail(chatId, id, callback),
                                             () -> silent.send(WRONG_COMMAND_MESSAGE, chatId));
        }
    }

    private void processCommand(Long chatId, Command payload) {
        switch (payload) {
            case TIMETABLE:
                Map<String, String> days = Arrays.stream(Day.values())
                                                 .map(Day::getMessage)
                                                 .collect(LinkedHashMap::new,
                                                          (map, item) -> map.put(item.getLeft(), item.getRight()),
                                                          Map::putAll);
                sendMap(chatId, days);
                break;
            case STUDENTS:
                sendList(chatId, studentsClient.getStudents(getUserGroup(chatId)));
                break;
            case TEACHERS:
                sendList(chatId, studentsClient.getTeachers(getUserGroup(chatId)));
                break;
            case LECTURES:
                sendList(chatId, studentsClient.getFiles(getUserGroup(chatId)).stream()
                                               .filter(fileApi -> fileApi.getType() == 1)
                                               .collect(Collectors.toList()));
                break;
            case TASKS:
                sendList(chatId, studentsClient.getFiles(getUserGroup(chatId)).stream()
                                               .filter(fileApi -> fileApi.getType() == 0)
                                               .collect(Collectors.toList()));
                break;
        }
    }

    @SneakyThrows
    private void processDetail(long chatId, String id, Callback callback) {
        switch (callback) {
            case STUDENT:
            case TEACHER:
                sendMessageWithText(chatId, studentsClient.getUser(id).getData());
                break;
            case DAY:
                sendMessageWithText(chatId, studentsClient.getLessons(getUserGroup(chatId))
                                                          .stream()
                                                          .filter(lessonApi -> Objects.equals(lessonApi.getWeekDay(),
                                                                                              Long.parseLong(id)))
                                                          .filter(lesson -> Objects.equals(lesson.getWeekNumber(),
                                                                                           getWeek()))
                                                          .sorted(Comparator.comparingLong(LessonApi::getLessonTime))
                                                          .map(LessonApi::getData)
                                                          .collect(Collectors.joining(System.lineSeparator())));
                break;
            case LECTURE:
            case TASK:
                sendFile(chatId, new InputFile(studentsClient.getFile(Long.parseLong(id)).body().asInputStream(), id));
                break;
        }
    }

    private void sendWelcome(Long chatId) {
        if (botUserRepository.findById(chatId).isEmpty()) {
            List<List<InlineKeyboardButton>> keyboard =
                    List.of(List.of(new InlineKeyboardButton()
                                            .setText(AUTHORIZE_BUTTON_NAME)
                                            .setUrl(redirectUri + "/login?state=" + chatId)));
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup().setKeyboard(keyboard);

            sendMessageWithMarkup(chatId, "Пожалуйста, авторизируйтесь", markup);
        } else {
            sendMenu(chatId);
        }
    }

    private void sendList(Long chatId, List<? extends BotData> list) {
        if (list.isEmpty()) {
            sendMessageWithText(chatId, NO_ELEMENTS_MESSAGE);
        } else {
            sendMessageWithMarkup(chatId, TelegramBot.SELECT_MESSAGE, createMarkup(list));
        }
    }

    private void sendMessageWithText(Long chatId, String info) {
        if (StringUtils.isEmpty(info)) {
                sendMessageWithText(chatId, NO_ELEMENTS_MESSAGE);
        }
        SendMessage message = new SendMessage()
                .setChatId(chatId)
                .setText(info);
        silent.execute(message);
    }

    @SneakyThrows
    private void sendFile(Long chatId, InputFile file) {
        SendDocument message = new SendDocument()
                .setChatId(chatId)
                .setDocument(file);
        sender.sendDocument(message);
    }

    private void sendMessageWithMarkup(Long chatId, String text, InlineKeyboardMarkup markup) {
        SendMessage message = new SendMessage()
                .setChatId(chatId)
                .setText(text)
                .setReplyMarkup(markup);

        silent.execute(message);
    }

    private InlineKeyboardMarkup createMarkup(Collection<? extends BotData> collection) {
        List<List<InlineKeyboardButton>> keyboard = collection.stream()
                                                              .map(element -> new InlineKeyboardButton()
                                                                      .setText(element.getDisplayName())
                                                                      .setCallbackData(element.getCallbackName() +
                                                                                               SPACE +
                                                                                               element.getId()))
                                                              .map(List::of)
                                                              .collect(Collectors.toList());

        return new InlineKeyboardMarkup().setKeyboard(keyboard);
    }

    private Long getUserGroup(Long chatId) {
        return botUserRepository.findById(chatId).orElseThrow().getGroupId();
    }

    private Long getWeek() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
        LocalDate now = LocalDate.now(ZoneId.systemDefault());

        String dateStr = firstWeekStart != null
                ? firstWeekStart
                : now.format(formatter);
        LocalDate date = LocalDate.parse(dateStr, formatter);

        int weeks = Period.between(date, now).getDays() / 7;
        return (weeks % 2 == 0)
                ? 1L
                : 2L;
    }
}