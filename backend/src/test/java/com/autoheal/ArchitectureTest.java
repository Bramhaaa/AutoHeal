package com.autoheal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;

@AnalyzeClasses(packages = "com.autoheal")
class ArchitectureTest {

  @ArchTest
  static final ArchRule noFieldInjection =
      noFields()
          .should()
          .beAnnotatedWith(Autowired.class)
          .because("Use constructor injection with final fields (AGENTS.md).");

  @ArchTest
  static final ArchRule domainMustNotDependOnOuterLayers =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..api..", "..service..", "..infra..")
          .allowEmptyShould(true)
          .because("Domain layer must stay pure and independent of api/service/infra.");

  @ArchTest
  static final ArchRule apiMustNotBypassServiceToInfra =
      noClasses()
          .that()
          .resideInAPackage("..api..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..infra..")
          .allowEmptyShould(true)
          .because("Controllers (api) must call service, never infra directly.");

  @ArchTest
  static final ArchRule infraMustNotDependOnApi =
      noClasses()
          .that()
          .resideInAPackage("..infra..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..api..")
          .allowEmptyShould(true)
          .because("Infrastructure adapters must not depend on HTTP/API layer.");
}
