# Dynamic Validation Spring Boot Project

This project demonstrates:

- Spring Boot 3.5.6
- Java 17
- Two DTOs: `UserDto`, `CustomerDto`
- Three validation groups/types: create, update, approve
- No validation annotations on DTOs
- Separate custom validator class for each field/rule
- Custom `@ValidPhone` annotation kept separate from DTOs
- Dynamic fields supplied in the request
- Dynamic DTO selection
- Dynamic validation type selection
- Cross-field validation
- Runtime field configuration
- No `UserValidationHandler`

## Run

Requirements:

- Java 17+
- Maven 3.9+

```bash
mvn clean spring-boot:run
```

Application:

```text
http://localhost:8080
```

## Main validation endpoint

POST:

```text
/api/validation
```

Example:

```json
{
  "dtoType": "USER",
  "validationType": "USER_CREATE",
  "data": {
    "firstName": "John",
    "lastName": "Smith",
    "email": "john@gmail.com",
    "phone": "9876543210",
    "password": "password123",
    "confirmPassword": "password123",
    "age": 25
  }
}
```

Expected:

```json
{
  "valid": true,
  "dtoType": "USER",
  "validationType": "USER_CREATE",
  "errors": []
}
```

## Invalid example

```json
{
  "dtoType": "USER",
  "validationType": "USER_CREATE",
  "data": {
    "firstName": "",
    "lastName": "Smith",
    "email": "wrong-email",
    "phone": "123",
    "password": "abc",
    "confirmPassword": "xyz",
    "age": 16
  }
}
```

## Dynamic fields from request

You can add fields for a particular request using `additionalFields`.

Example:

```json
{
  "dtoType": "USER",
  "validationType": "USER_UPDATE",
  "additionalFields": {
    "firstName": "required"
  },
  "data": {
    "firstName": "",
    "email": "john@gmail.com",
    "phone": "9876543210",
    "age": 25
  }
}
```

Here `firstName` was not part of `USER_UPDATE` initially. It is added dynamically for this validation request.

## Runtime group configuration

You can also permanently add a field to a configured validation group.

POST:

```text
/api/validation-config/fields?validationType=USER_UPDATE&fieldName=firstName&validators=required
```

After that, `firstName` becomes part of `USER_UPDATE`.

## Architecture

```text
DynamicValidationRequest
        |
        v
      DTO Registry
        |
        v
     ObjectMapper
        |
        v
 DynamicValidationEngine
        |
        +---- ValidationGroup
        |       |
        |       +---- fields
        |       +---- cross-field rules
        |
        +---- RequiredValidator
        +---- EmailValidator
        +---- PhoneValidator
        +---- AgeValidator
        +---- CreditLimitValidator
        |
        +---- PasswordMatchValidator
        +---- DateRangeValidator
```

The DTOs contain no validation annotations and no validation logic.

## Adding another validator

Implement:

```java
@Component
public class MyValidator implements FieldValidator {

    @Override
    public String getName() {
        return "myValidator";
    }

    @Override
    public boolean supports(Class<?> fieldType) {
        return true;
    }

    @Override
    public boolean isValid(Object value) {
        return true;
    }

    @Override
    public String getMessage() {
        return "Invalid value";
    }
}
```

Then use:

```json
{
  "additionalFields": {
    "someField": "myValidator"
  }
}
```

## Important note about @ValidPhone

`@ValidPhone` is intentionally NOT placed on `UserDto`.

It is included to demonstrate the custom-annotation/validator contract separately. The runtime dynamic framework uses `FieldValidator` beans because that allows fields and validators to be selected dynamically without modifying DTO classes.

If you want the actual Bean Validation engine (`jakarta.validation.Validator`) to execute custom annotations that are not physically present on DTOs, Hibernate Validator programmatic constraint mapping can be added as a second layer. The current project keeps the runtime rule selection simpler and fully dynamic.
