def first_even(values: list[int]) -> int | None:
  return next((value for value in values if value % 2 == 0), None)
