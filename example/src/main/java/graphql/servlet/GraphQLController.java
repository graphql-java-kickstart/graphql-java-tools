package graphql.servlet;

import graphql.schema.GraphQLSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@RestController
@RequestMapping("/graphql")
public class GraphQLController extends GraphQLServlet {

    @Autowired
    private GraphQLSchema graphQLSchema;

    @PostConstruct
    public void postConstruct() {
        this.readOnlySchema = graphQLSchema;
        this.schema = graphQLSchema;
    }

    @GetMapping
    public void handleGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        this.doGet(request, response);
    }

    @PostMapping
    public void handlePost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        this.doPost(request, response);
    }
}
