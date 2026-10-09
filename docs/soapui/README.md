# SoapUI assessment evidence

The interviewer’s WSDL was imported using the installed **SoapUI 5.10.0** on 9 October 2026:

```text
http://webservices.oorsprong.org/websamples.countryinfo/CountryInfoService.wso?WSDL
```

The saved `country-info-soapui-project.xml` contains both SOAP 1.1 and SOAP 1.2 bindings, with **21 operations per binding** and cached WSDL definitions. Open it through File > Import Project in SoapUI. The [actual app screenshot](soapui-import.png) shows the existing imported CountryInfoService project; the workspace was originally imported on 8 October and the screenshot was captured on 9 October. The repository project was freshly generated with the installed SoapUI importer and executed with its actual test runner on 9 October.

## Live checks

| Request | Expected value | Verified |
| --- | --- | --- |
| CountryISOCode: Tanzania | TZ | Passed |
| FullCountryInfo: TZ | Tanzania | Passed |
| CountryISOCode: South Africa | ZA | Passed |

Three test cases and nine assertions passed: each response was SOAP, was not a SOAP fault, and matched the expected XPath value. See [JUnit results](results/TEST-CountryInfo_smoke_tests.xml) and request/response samples beside that file. Environment properties were removed from the exported JUnit report; the test cases and outcomes are retained.

```bash
./scripts/test-soapui.sh
# Other installations:
SOAPUI_HOME=/path/to/SoapUI ./scripts/test-soapui.sh
```

The script writes fresh runner reports to ignored `target/soapui-reports/`. The main Maven suite uses local HTTP stubs and does not depend on this provider being available.

## Multi-word names

A live sentence-case request for `South africa` returned “No country found by that name.” The provider accepts `South Africa` and returns ZA. The service first tries the requested sentence-case name, then makes one title-case lookup if an unknown-country response concerns a multi-word name. Network errors and timeouts keep their original failure status. Tests cover this behavior and unknown-country results; country details are still taken from FullCountryInfo.

## Endpoint security

The brief supplies an HTTP endpoint. An HTTPS connection attempt failed from this environment, so the working URL remains configurable and HTTP is disclosed as a limitation. Do not change to HTTPS without first verifying the provider’s supported endpoint and certificate chain.

Saved text samples use LF line endings with trailing whitespace removed; request and response content is preserved.
