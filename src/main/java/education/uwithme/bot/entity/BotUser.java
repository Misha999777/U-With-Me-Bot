package education.uwithme.bot.entity;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BotUser implements Serializable {

    @Id
    private Long chatId;
    private Long groupId;
    @ElementCollection(fetch = FetchType.EAGER)
    private List<Integer> loginMessageIds = new ArrayList<>();
}
