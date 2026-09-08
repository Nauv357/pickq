package com.tiku.mapper;

import com.tiku.model.Question;
import com.tiku.model.handler.OptionItemTypeHandler;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 会话抽取题目关联（practice_session_question）。
 * 注解 SQL：会话回顾页需要展示完整题目列表（含未作答的）。
 */
public interface PracticeSessionQuestionMapper {

    @Insert("INSERT INTO practice_session_question (session_id, question_id, sort) VALUES (#{sessionId}, #{questionId}, #{sort})")
    int insert(Long sessionId, Long questionId, int sort);

    @Select("SELECT q.* FROM practice_session_question psq JOIN question q ON q.id = psq.question_id " +
            "WHERE psq.session_id = #{sessionId} ORDER BY psq.sort")
    @Results(id = "questionMap", value = {
            @Result(column = "options", property = "options", typeHandler = OptionItemTypeHandler.class)
    })
    List<Question> selectQuestions(Long sessionId);

    //提交作答时的归属校验：题目必须属于该会话的抽取列表（防游离记录挂到任意会话）
    @Select("SELECT COUNT(*) FROM practice_session_question WHERE session_id = #{sessionId} AND question_id = #{questionId}")
    int countBySessionAndQuestion(Long sessionId, Long questionId);

    //删除题库时级联清理
    @Delete("DELETE FROM practice_session_question WHERE session_id IN (SELECT id FROM practice_session WHERE bank_id = #{bankId})")
    int deleteByBankId(Long bankId);
}
