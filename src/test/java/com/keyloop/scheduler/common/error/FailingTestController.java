package com.keyloop.scheduler.common.error;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class FailingTestController {

  @GetMapping("/api/test/boom")
  String boom() {
    throw new IllegalStateException("SELECT * FROM secret");
  }
}
