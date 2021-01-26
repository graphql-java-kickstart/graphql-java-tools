package graphql.kickstart.tools

/**
 *
 * @author dreamylost@outlook.com
 * @version 1.0,2021/1/26
 */
interface StarWarsEntity {}

interface Character extends StarWarsEntity {
    String getName();
}

class Human implements Character {
    @Override
    String getName() {
        return null
    }
}

class Droid implements Character {
    @Override
    String getName() {
        return null
    }
}

class Planet implements StarWarsEntity {
    String location
}