find_program(GIT git REQUIRED)
execute_process(COMMAND ${GIT} apply --check ${PATCH}
    WORKING_DIRECTORY ${SOURCE} RESULT_VARIABLE applicable ERROR_VARIABLE detail)
if(applicable EQUAL 0)
    execute_process(COMMAND ${GIT} apply ${PATCH}
        WORKING_DIRECTORY ${SOURCE} COMMAND_ERROR_IS_FATAL ANY)
else()
    execute_process(COMMAND ${GIT} apply --reverse --check ${PATCH}
        WORKING_DIRECTORY ${SOURCE} RESULT_VARIABLE applied ERROR_QUIET)
    if(NOT applied EQUAL 0)
        message(FATAL_ERROR "Upstream patch cannot be applied: ${PATCH}\n${detail}")
    endif()
endif()
