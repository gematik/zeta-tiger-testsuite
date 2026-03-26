#
# #%L
# ZETA Testsuite
# %%
# (C) achelos GmbH, 2025, licensed for gematik GmbH
# %%
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# *******
#
# For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
# #L%
#

#language:de

@UseCase_COVERAGE_01
Funktionalität: Globale Markierung begründet nicht ausgeführter Testaspekte

  @Ignore
  @not_impl
  @nicht_getestet_begruendet
  @no_proxy
  @A_26988
  @A_27264
  @A_25795
  @A_25796
  @A_25799
  Szenariogrundriss: Begründet nicht ausgeführte Testaspekte
    Wenn TGR setze lokale Variable "ausschlussGrund" auf "<grund>"
    Dann TGR setze lokale Variable "ausgeschlossenerTA" auf "<ta>"
    Und TGR gebe variable "ausgeschlossenerTA" aus
    Und TGR gebe variable "ausschlussGrund" aus

    @nicht_getestet_container_fehlt
    @TA_A_26988_08
    Beispiele: TA_A_26988_08
      | ta            | grund                                                                  |
      | TA_A_26988_08 | Notification Service ist in der aktuellen Testumgebung nicht verfügbar. |

    @nicht_getestet_container_fehlt
    @TA_A_27264_06
    Beispiele: TA_A_27264_06
      | ta            | grund                                                                  |
      | TA_A_27264_06 | Notification Service ist in der aktuellen Testumgebung nicht verfügbar. |

    @nicht_getestet_container_fehlt
    @TA_A_27264_15
    Beispiele: TA_A_27264_15
      | ta            | grund                                                                  |
      | TA_A_27264_15 | Notification Service ist in der aktuellen Testumgebung nicht verfügbar. |

    @nicht_getestet_container_fehlt
    @TA_A_27264_24
    Beispiele: TA_A_27264_24
      | ta            | grund                                                                  |
      | TA_A_27264_24 | Notification Service ist in der aktuellen Testumgebung nicht verfügbar. |

    @nicht_getestet_container_fehlt
    @TA_A_25795_06
    Beispiele: TA_A_25795_06
      | ta            | grund                                                                  |
      | TA_A_25795_06 | Notification Service ist in der aktuellen Testumgebung nicht verfügbar. |

    @nicht_getestet_container_fehlt
    @TA_A_25796_15
    Beispiele: TA_A_25796_15
      | ta            | grund                                                                  |
      | TA_A_25796_15 | Notification Service ist in der aktuellen Testumgebung nicht verfügbar. |

    @nicht_getestet_container_fehlt
    @TA_A_25799_06
    Beispiele: TA_A_25799_06
      | ta            | grund                                                                  |
      | TA_A_25799_06 | Notification Service ist in der aktuellen Testumgebung nicht verfügbar. |

    @nicht_getestet_scope_ausgenommen
    @TA_A_26988_04
    Beispiele: TA_A_26988_04
      | ta            | grund                                                                             |
      | TA_A_26988_04 | PEP-Datenbank ist im aktuellen Scope der Testumgebung kein eigenständiges Prüfobjekt. |

    @nicht_getestet_scope_ausgenommen
    @TA_A_26988_09
    Beispiele: TA_A_26988_09
      | ta            | grund                                                                             |
      | TA_A_26988_09 | Management Service ist im aktuellen Scope der Testumgebung kein eigenständiges Prüfobjekt. |

    @nicht_getestet_scope_ausgenommen
    @TA_A_27264_07
    Beispiele: TA_A_27264_07
      | ta            | grund                                                                             |
      | TA_A_27264_07 | Management Service ist im aktuellen Scope der Testumgebung kein eigenständiges Prüfobjekt. |

    @nicht_getestet_scope_ausgenommen
    @TA_A_27264_16
    Beispiele: TA_A_27264_16
      | ta            | grund                                                                             |
      | TA_A_27264_16 | Management Service ist im aktuellen Scope der Testumgebung kein eigenständiges Prüfobjekt. |

    @nicht_getestet_scope_ausgenommen
    @TA_A_27264_25
    Beispiele: TA_A_27264_25
      | ta            | grund                                                                             |
      | TA_A_27264_25 | Management Service ist im aktuellen Scope der Testumgebung kein eigenständiges Prüfobjekt. |

    @nicht_getestet_scope_ausgenommen
    @TA_A_25795_02
    Beispiele: TA_A_25795_02
      | ta            | grund                                                                             |
      | TA_A_25795_02 | PEP-Datenbank ist im aktuellen Scope der Testumgebung kein eigenständiges Prüfobjekt. |

    @nicht_getestet_scope_ausgenommen
    @TA_A_25795_07
    Beispiele: TA_A_25795_07
      | ta            | grund                                                                             |
      | TA_A_25795_07 | Management Service ist im aktuellen Scope der Testumgebung kein eigenständiges Prüfobjekt. |

    @nicht_getestet_scope_ausgenommen
    @TA_A_25796_04
    Beispiele: TA_A_25796_04
      | ta            | grund                                                                             |
      | TA_A_25796_04 | PEP-Datenbank ist im aktuellen Scope der Testumgebung kein eigenständiges Prüfobjekt. |

    @nicht_getestet_scope_ausgenommen
    @TA_A_25796_18
    Beispiele: TA_A_25796_18
      | ta            | grund                                                                             |
      | TA_A_25796_18 | Management Service ist im aktuellen Scope der Testumgebung kein eigenständiges Prüfobjekt. |

    @nicht_getestet_scope_ausgenommen
    @TA_A_25799_02
    Beispiele: TA_A_25799_02
      | ta            | grund                                                                             |
      | TA_A_25799_02 | PEP-Datenbank ist im aktuellen Scope der Testumgebung kein eigenständiges Prüfobjekt. |

    @nicht_getestet_scope_ausgenommen
    @TA_A_25799_07
    Beispiele: TA_A_25799_07
      | ta            | grund                                                                             |
      | TA_A_25799_07 | Management Service ist im aktuellen Scope der Testumgebung kein eigenständiges Prüfobjekt. |

    @nicht_getestet_soll_anforderung
    @TA_A_26640_03
    Beispiele: TA_A_26640_03
      | ta            | grund                                                                                           |
      | TA_A_26640_03 | Der Testaspekt adressiert eine SOLL-Anforderung (HTTP/3) und wird in der aktuellen Iteration nicht umgesetzt. |

    @nicht_getestet_soll_anforderung
    @TA_A_26964_01
    Beispiele: TA_A_26964_01
      | ta            | grund                                                                                           |
      | TA_A_26964_01 | Der Testaspekt adressiert eine SOLL-Anforderung und wird in der aktuellen Iteration nicht umgesetzt. |

    @nicht_getestet_soll_anforderung
    @TA_A_27867_02
    Beispiele: TA_A_27867_02
      | ta            | grund                                                                                           |
      | TA_A_27867_02 | Der Testaspekt adressiert eine SOLL-Anforderung und wird in der aktuellen Iteration nicht umgesetzt. |

    @nicht_getestet_soll_anforderung
    @TA_A_27867_03
    Beispiele: TA_A_27867_03
      | ta            | grund                                                                                           |
      | TA_A_27867_03 | Der Testaspekt adressiert eine SOLL-Anforderung und wird in der aktuellen Iteration nicht umgesetzt. |

    @nicht_getestet_soll_anforderung
    @TA_A_27867_04
    Beispiele: TA_A_27867_04
      | ta            | grund                                                                                           |
      | TA_A_27867_04 | Der Testaspekt adressiert eine SOLL-Anforderung und wird in der aktuellen Iteration nicht umgesetzt. |
