package com.investary.email.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TrackingLinkUnwrapperTest {

    private final TrackingLinkUnwrapper unwrapper = new TrackingLinkUnwrapper();

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            https://y1.r.ap-southeast-1.awstrack.me/L0/https:%2F%2Fstatic.tcbs.com.vn%2Foneclick%2FVIC.pdf%3Fa=1%26b=2/1/msg/sig=258 | https://static.tcbs.com.vn/oneclick/VIC.pdf?a=1&b=2
            https://y1.r.us-east-1.awstrack.me/L0/https:%2F%2Fexample.com%2Fa+b.pdf/1/msg/sig                                       | https://example.com/a+b.pdf
            https://www.tcbs.com.vn/report.pdf                                                                                       | https://www.tcbs.com.vn/report.pdf
            https://y1.r.ap-southeast-1.awstrack.me/L0/javascript:alert(1)/1/msg/sig                                                 | https://y1.r.ap-southeast-1.awstrack.me/L0/javascript:alert(1)/1/msg/sig
            https://click.example.com/L0/https:%2F%2Fevil.example/1/x                                                                | https://click.example.com/L0/https:%2F%2Fevil.example/1/x
            """)
    void unwrapsOnlyKnownTrackersWithHttpTargets(String input, String expected) {
        assertThat(unwrapper.unwrap(input)).isEqualTo(expected);
    }
}
