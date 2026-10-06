package dio.budgeting.infrastructure.ai;

import com.marcusrdrigues.noxguard.springai.GuardedToolCallbacks;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The assistant's flow: speech to text, the model with the guarded tools, and text to speech.
 *
 * <p>Every answer gets its own tools from {@link GuardedToolCallbacks#forNewAnswer()}, so the limits of
 * the {@code ToolPolicy} count per answer.
 */
@Component
public class BudgetAssistant {
    private final ChatClient chatClient;
    private final GuardedToolCallbacks guardedTools;
    private final TranscriptionModel transcriptionModel;
    private final TextToSpeechModel textToSpeechModel;

    public BudgetAssistant(ChatClient.Builder chatClientBuilder,
                           @Value("classpath:prompts/system-message.st") Resource systemPrompt,
                           GuardedToolCallbacks guardedTools,
                           TranscriptionModel transcriptionModel,
                           TextToSpeechModel textToSpeechModel) throws IOException {
        this.chatClient = chatClientBuilder
                .defaultSystem(systemPrompt.getContentAsString(StandardCharsets.UTF_8))
                .build();
        this.guardedTools = guardedTools;
        this.transcriptionModel = transcriptionModel;
        this.textToSpeechModel = textToSpeechModel;
    }

    /** Answers a message in text. */
    public String answer(String message) {
        return chatClient.prompt()
                .user(message)
                .tools(guardedTools.forNewAnswer().callbacks())
                .call()
                .content();
    }

    /** Answers a recorded message with speech (MP3). */
    public byte[] answerByVoice(Resource audio) {
        String message = transcriptionModel.transcribe(audio);
        return textToSpeechModel.call(answer(message));
    }
}
