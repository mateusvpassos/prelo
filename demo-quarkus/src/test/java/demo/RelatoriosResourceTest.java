package demo;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class RelatoriosResourceTest {
    @Test
    void geraPdfXlsxEHtml() {
        byte[] pdf = given().when().get("/relatorios/vendas").then()
                .statusCode(200)
                .contentType("application/pdf")
                .header("Content-Disposition", containsString("vendas.pdf"))
                .extract().asByteArray();
        assertEquals("%PDF-", new String(pdf, 0, 5));
        given().when().get("/relatorios/vendas?formato=xlsx").then().statusCode(200);
        given().when().get("/relatorios/vendas?formato=html").then().statusCode(200).body(containsString("Região: Norte"));
    }
}
