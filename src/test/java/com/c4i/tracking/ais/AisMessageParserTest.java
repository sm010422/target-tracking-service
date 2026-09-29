package com.c4i.tracking.ais;

import com.c4i.tracking.kafka.TargetEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AisMessageParserTest {

    private final AisMessageParser parser = new AisMessageParser();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode json(String raw) throws Exception {
        return objectMapper.readTree(raw);
    }

    @Test
    @DisplayName("정상 PositionReport를 targetType=SHIP인 TargetEvent로 변환한다")
    void parsesValidPositionReport() throws Exception {
        JsonNode message = json("""
            {
              "MessageType": "PositionReport",
              "MetaData": {"MMSI": 440123456, "latitude": 35.1, "longitude": 129.0},
              "Message": {"PositionReport": {"Sog": 12.0, "TrueHeading": 90, "Cog": 91.0}}
            }
            """);

        TargetEvent event = parser.parse(message);

        assertThat(event).isNotNull();
        assertThat(event.getTargetId()).isEqualTo("440123456");
        assertThat(event.getTargetType()).isEqualTo("SHIP");
        assertThat(event.getLatitude()).isEqualTo(35.1);
        assertThat(event.getLongitude()).isEqualTo(129.0);
        assertThat(event.getAltitude()).isEqualTo(0.0);
        assertThat(event.getSpeed()).isCloseTo(22.224, within(0.01)); // 12kn * 1.852
        assertThat(event.getHeading()).isEqualTo(90.0);
    }

    @Test
    @DisplayName("TrueHeading이 511(값 없음)이면 Cog로 대체한다")
    void fallsBackToCogWhenTrueHeadingUnavailable() throws Exception {
        JsonNode message = json("""
            {
              "MessageType": "PositionReport",
              "MetaData": {"MMSI": 440123456, "latitude": 35.1, "longitude": 129.0},
              "Message": {"PositionReport": {"Sog": 5.0, "TrueHeading": 511, "Cog": 200.5}}
            }
            """);

        TargetEvent event = parser.parse(message);

        assertThat(event.getHeading()).isEqualTo(200.5);
    }

    @Test
    @DisplayName("TrueHeading, Cog 둘 다 없으면 heading은 null이다")
    void headingIsNullWhenNeitherAvailable() throws Exception {
        JsonNode message = json("""
            {
              "MessageType": "PositionReport",
              "MetaData": {"MMSI": 440123456, "latitude": 35.1, "longitude": 129.0},
              "Message": {"PositionReport": {"Sog": 5.0}}
            }
            """);

        TargetEvent event = parser.parse(message);

        assertThat(event.getHeading()).isNull();
    }

    @Test
    @DisplayName("PositionReport가 아닌 메시지 타입은 무시한다")
    void ignoresNonPositionReportMessages() throws Exception {
        JsonNode message = json("""
            {"MessageType": "ShipStaticData", "MetaData": {"MMSI": 440123456}}
            """);

        assertThat(parser.parse(message)).isNull();
    }

    @Test
    @DisplayName("MMSI가 없으면 무시한다")
    void ignoresMessagesWithoutMmsi() throws Exception {
        JsonNode message = json("""
            {
              "MessageType": "PositionReport",
              "MetaData": {"latitude": 35.1, "longitude": 129.0},
              "Message": {"PositionReport": {"Sog": 5.0}}
            }
            """);

        assertThat(parser.parse(message)).isNull();
    }

    @Test
    @DisplayName("위치 정보가 없으면 무시한다")
    void ignoresMessagesWithoutPosition() throws Exception {
        JsonNode message = json("""
            {
              "MessageType": "PositionReport",
              "MetaData": {"MMSI": 440123456},
              "Message": {"PositionReport": {"Sog": 5.0}}
            }
            """);

        assertThat(parser.parse(message)).isNull();
    }
}
