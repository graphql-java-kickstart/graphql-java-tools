package com.coxautodev.graphql.tools.example;

import com.coxautodev.graphql.tools.example.types.Character;
import com.coxautodev.graphql.tools.example.types.Droid;
import com.coxautodev.graphql.tools.example.types.Episode;
import com.coxautodev.graphql.tools.example.types.Human;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class CharacterRepository {

    private Map<String, Character> characters;
    private Map<Episode, Character> heroes;

    public CharacterRepository() {
        Human lukeSkywalker = new Human("1000", "Luke Skywalker", Arrays.asList(Episode.NEWHOPE, Episode.JEDI, Episode.EMPIRE),"Tatooine");
        Human darthVader = new Human("1001", "Darth Vader", Arrays.asList(Episode.NEWHOPE, Episode.JEDI, Episode.EMPIRE), "Tatooine");
        Human hanSolo = new Human("1002", "Han Solo", Arrays.asList(Episode.NEWHOPE, Episode.JEDI, Episode.EMPIRE), null);
        Human leiaOrgana = new Human("1003", "Leia Organa", Arrays.asList(Episode.NEWHOPE, Episode.JEDI, Episode.EMPIRE), "Alderaan");
        Human wilhuffTarkin = new Human("1004", "Wilhuff Tarkin", Collections.singletonList(Episode.NEWHOPE), null);

        Droid c3po = new Droid("2000", "C-3PO", Arrays.asList(Episode.NEWHOPE, Episode.JEDI, Episode.EMPIRE), "Protocol");
        Droid aretoo = new Droid("2001", "R2-D2", Arrays.asList(Episode.NEWHOPE, Episode.JEDI, Episode.EMPIRE), "Astromech");

        lukeSkywalker.addFriends(hanSolo, leiaOrgana, c3po, aretoo);
        darthVader.addFriends(wilhuffTarkin);
        hanSolo.addFriends(lukeSkywalker, leiaOrgana, aretoo);
        leiaOrgana.addFriends(lukeSkywalker, hanSolo, c3po, aretoo);
        wilhuffTarkin.addFriends(darthVader);

        c3po.addFriends(lukeSkywalker, hanSolo, leiaOrgana, aretoo);
        aretoo.addFriends(lukeSkywalker, hanSolo, leiaOrgana);

        this.characters = Collections.unmodifiableMap(Stream.of(lukeSkywalker, darthVader, hanSolo, leiaOrgana, wilhuffTarkin, c3po, aretoo)
            .collect(Collectors.toMap(Character::getId, Function.identity())));

        Map<Episode, Character> heroes = new HashMap<>();
        heroes.put(Episode.NEWHOPE, lukeSkywalker);
        heroes.put(Episode.EMPIRE, aretoo);
        heroes.put(Episode.JEDI, darthVader);

        this.heroes = Collections.unmodifiableMap(heroes);
    }

    public Map<String, Character> getCharacters() {
        return characters;
    }

    public Map<Episode, Character> getHeroes() {
        return heroes;
    }
}
