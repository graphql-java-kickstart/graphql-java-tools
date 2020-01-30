package graphql.kickstart.tools.example.resolvers;

import graphql.kickstart.tools.GraphQLQueryResolver;
import graphql.kickstart.tools.example.CharacterRepository;
import graphql.kickstart.tools.example.types.Character;
import graphql.kickstart.tools.example.types.Droid;
import graphql.kickstart.tools.example.types.Episode;
import graphql.kickstart.tools.example.types.Human;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class Query implements GraphQLQueryResolver {

    @Autowired
    private CharacterRepository characterRepository;

    public Character hero(Episode episode) {
        return episode != null ? characterRepository.getHeroes().get(episode) : characterRepository.getCharacters().get("1000");
    }

    public Human human(String id, DataFetchingEnvironment env) {
        return (Human) characterRepository.getCharacters().values().stream()
            .filter(character -> character instanceof Human && character.getId().equals(id))
            .findFirst()
            .orElseGet(null);
    }

    public Droid droid(String id) {
        return (Droid) characterRepository.getCharacters().values().stream()
            .filter(character -> character instanceof Droid && character.getId().equals(id))
            .findFirst()
            .orElseGet(null);
    }

    public Character character(String id) {
        return characterRepository.getCharacters().get(id);
    }
}
